package com.ottogroup.bi.soda.bottler.api

import scala.Array.canBuildFrom
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.Try
import com.bethecoder.ascii_table.ASCIITable
import com.ottogroup.bi.soda.dsl.views.ViewUrlParser
import akka.actor.ActorSystem
import akka.event.Logging
import akka.util.Timeout
import spray.client.pipelining.Get
import spray.client.pipelining.WithTransformerConcatenation
import spray.client.pipelining.sendReceive
import spray.client.pipelining.unmarshal
import spray.httpx.SprayJsonSupport.sprayJsonUnmarshaller
import spray.http.Uri
import spray.http.Uri._
import spray.http.Uri.Path.SingleSlash
import com.ottogroup.bi.soda.Settings
import scala.util.matching.Regex
import scala.collection.immutable.Map
import scala.collection.mutable.HashMap

object CliFormat { // FIXME: a more generic parsing would be cool...
  def serialize(o: Any): String = {
    val sb = new StringBuilder()
    o match {
      case as: ActionStatusList => {
        if (as.actions.size > 0) {
          val header = Array("ACTOR", "STATUS", "STARTED", "DESC", "TARGET_VIEW", "PROPS")
          val running = as.actions.map(p => {
            val (s, d, t): (String, String, String) =
              if (p.runStatus.isDefined) {
                (p.runStatus.get.started.toString, p.runStatus.get.description, p.runStatus.get.targetView)
              } else {
                ("", "", "")
              }
            Array(p.actor, p.status, s, d, t, p.properties.mkString(","))
          }).toArray
          val queued = as.queues.flatMap(q => q._2.map(e => Array(s"${q._1}-queue", "queued", "no", e.description, e.targetView, e.properties.getOrElse("").toString))).toArray
          val all = running ++ queued
          sb.append(ASCIITable.getInstance.getTable(header, running ++ queued))
          sb.append(s"Total: ${all.size}\n")
        }
        sb.append("\n" + as.overview.map(el => s"${el._1} : ${el._2}").mkString("\n") + "\n")
      }

      case vl: ViewStatusList => {
        if (!vl.views.isEmpty) {
          sb.append(s"Details:\n")
          val header = Array("VIEW", "STATUS", "PROPS")
          val data = vl.views.map(d => Array(d.view, d.status, d.properties.mkString(","))).toArray
          sb.append(ASCIITable.getInstance.getTable(header, data))
          sb.append(s"Total: ${data.size}\n")
        }
        sb.append("\n" + vl.overview.map(el => s"${el._1}: ${el._2}").mkString("\n") + "\n")
      }

      case sc: SodaCommandStatus => {
        sb.append(s"id: ${sc.id}\n")
        sb.append(s"start: ${sc.start}\n")
        sb.append(s"status: ${sc.status}\n")
      }

      case f: Future[_] => {
        sb.append(s"submitted; isCompleted: ${f.isCompleted}\n")
      }

      case s: Seq[_] => {
        sb.append(s.map(el => serialize(el)).mkString("\n"))
      }

      case _ => sb.append(o)
    }
    sb.toString
  }
}

class SodaRestClient extends SodaInterface {

  var host = "localhost"
  var port = Settings().port

  import SodaJsonProtocol._

  implicit val system = ActorSystem("soda-spray-client")
  import system.dispatcher // execution context for futures below
  implicit val timeout = Timeout(10.days)
  val log = Logging(system, getClass)

  def get[T](path: String, query: Map[String, String]): Future[T] = {
    val pipeline = path match {
      case u: String if u.startsWith("/views") => sendReceive ~> unmarshal[ViewStatusList]
      case u: String if u.startsWith("/actions") => sendReceive ~> unmarshal[ActionStatusList]
      case u: String if u.startsWith("/materialize") => sendReceive ~> unmarshal[SodaCommandStatus]
      case u: String if u.startsWith("/invalidate") => sendReceive ~> unmarshal[SodaCommandStatus]
      case u: String if u.startsWith("/newdata") => sendReceive ~> unmarshal[SodaCommandStatus]
      case u: String if u.startsWith("/commands") => sendReceive ~> unmarshal[List[SodaCommandStatus]]
      case _ => throw new RuntimeException("Unsupported query path: " + path)
    }
    val uri = Uri.from("http", "", host, port, path) withQuery (query)
    println("Calling Soda API URL: " + uri)
    pipeline(Get(uri)).asInstanceOf[Future[T]]
  }

  private def paramsFrom(params: (String, Option[Any])*): Map[String, String] = {
    params.filter(_._2.isDefined)
      .map(p => (p._1 -> p._2.get.toString))
      .toMap
  }

  def shutdown() {
    system.shutdown()
  }

  def materialize(viewUrlPath: String): SodaCommandStatus = {
    Await.result(get[SodaCommandStatus](s"/materialize/${viewUrlPath}", Map()), 10.days)
  }

  def invalidate(viewUrlPath: String): SodaCommandStatus = {
    Await.result(get[SodaCommandStatus](s"/invalidate/${viewUrlPath}", Map()), 3600 seconds)
  }

  def newdata(viewUrlPath: String): SodaCommandStatus = {
    Await.result(get[SodaCommandStatus](s"/newdata/${viewUrlPath}", Map()), 3600 seconds)
  }

  def commandStatus(commandId: String): SodaCommandStatus = { null }

  def commands(status: Option[String], filter: Option[String]): List[SodaCommandStatus] = {
    Await.result(get[List[SodaCommandStatus]](s"/commands", paramsFrom(("status", status), ("filter", filter))), 3600 seconds)
  }

  def views(viewUrlPath: Option[String], status: Option[String], filter: Option[String], dependencies: Option[Boolean] = None): ViewStatusList = {
    Await.result(get[ViewStatusList](s"/views/${viewUrlPath.getOrElse("")}", paramsFrom(("status", status), ("filter", filter), ("dependencies", dependencies))), 3600 seconds)
  }

  def actions(status: Option[String], filter: Option[String]): ActionStatusList = {
    Await.result(get[ActionStatusList](s"/actions", paramsFrom(("status", status), ("filter", filter))), 3600 seconds)
  }
}

object SodaClientControl {
  val soda = new SodaRestClient()
  val ctrl = new SodaControl(soda)
  def main(args: Array[String]) {
    ctrl.run(args)
    soda.shutdown()
    System.exit(0)
  }
}

class SodaControl(soda: SodaInterface) {
  object Action extends Enumeration {
    val VIEWS, ACTIONS, MATERIALIZE, COMMANDS, INVALIDATE, NEWDATA = Value
  }
  import Action._

  case class Config(action: Option[Action.Value] = None, viewUrlPath: Option[String] = None, status: Option[String] = None, dependencies: Option[Boolean] = Some(false), filter: Option[String] = None)

  val parser = new scopt.OptionParser[Config]("soda-control") {
    override def showUsageOnError = true
    head("soda-control", "0.0.1")
    help("help") text ("print usage")
    
    cmd("views") action { (_, c) => c.copy(action = Some(VIEWS)) } text ("lists all view actors, along with their status") children (
      opt[String]('s', "status") action { (x, c) => c.copy(status = Some(x)) } optional () valueName ("<status>") text ("filter views by their status (e.g. 'transforming')"),
      opt[String]('v', "viewUrlPath") action { (x, c) => c.copy(viewUrlPath = Some(x)) } optional () valueName ("<viewUrlPath>") text ("view url path (e.g. 'my.database/MyView/Partition1/Partition2'). "),
      opt[String]('f', "filter") action { (x, c) => c.copy(filter = Some(x)) } optional () valueName ("<regex>") text ("regular expression to filter view display (e.g. 'my.database/.*/Partition1/.*'). "),
      opt[Unit]('d', "dependencies") action { (_, c) => c.copy(dependencies = Some(true)) } optional () text ("include dependencies"))

    cmd("actions") action { (_, c) => c.copy(action = Some(ACTIONS)) } text ("list status of action actors") children (
      opt[String]('s', "status") action { (x, c) => c.copy(status = Some(x)) } optional () valueName ("<status>") text ("filter actions by their status (e.g. 'queued, running, idle')"),
      opt[String]('f', "filter") action { (x, c) => c.copy(filter = Some(x)) } optional () valueName ("<regex>") text ("regular expression to filter action display (e.g. '.*hive-1.*'). "))

    cmd("commands") action { (_, c) => c.copy(action = Some(COMMANDS)) } text ("list commands") children (
      opt[String]('s', "status") action { (x, c) => c.copy(status = Some(x)) } optional () valueName ("<status>") text ("filter commands by their status (e.g. 'failed')"),
      opt[String]('f', "filter") action { (x, c) => c.copy(filter = Some(x)) } optional () valueName ("<regex>") text ("regular expression to filter command display (e.g. '.*201501.*'). "))

    cmd("materialize") action { (_, c) => c.copy(action = Some(MATERIALIZE)) } text ("materialize view(s)") children (
      opt[String]('v', "viewUrlPath") action { (x, c) => c.copy(viewUrlPath = Some(x)) } required () valueName ("<viewUrlPath>") text ("view url path (e.g. 'my.database/MyView/Partition1/Partition2'). "))

    cmd("invalidate") action { (_, c) => c.copy(action = Some(INVALIDATE)) } text ("invalidate view(s)") children (
      opt[String]('v', "viewUrlPath") action { (x, c) => c.copy(viewUrlPath = Some(x)) } required () valueName ("<viewUrlPath>") text ("view url path (e.g. 'my.database/MyView/Partition1/Partition2'). "))

    cmd("newdata") action { (_, c) => c.copy(action = Some(NEWDATA)) } text ("invalidate view(s)") children (
      opt[String]('v', "viewUrlPath") action { (x, c) => c.copy(viewUrlPath = Some(x)) } required () valueName ("<viewUrlPath>") text ("view url path (e.g. 'my.database/MyView/Partition1/Partition2'). "))

    checkConfig { c =>
      {
        if (!c.action.isDefined) failure("A command is required")
        else if (c.action.get.equals("materialize") && c.viewUrlPath.isDefined && Try(ViewUrlParser.parse(Settings().env, c.viewUrlPath.get)).isFailure) failure("Cannot parse view url path")
        else success
      }
    }
  }

  def run(args: Array[String]) {
    parser.parse(args, Config()) match {
      case Some(config) => {
        println("Starting " + config.action.get.toString + " ...")
        try {
          val res = config.action.get match {
            case ACTIONS => {
              soda.actions(config.status, config.filter)
            }
            case VIEWS => {
              soda.views(config.viewUrlPath, config.status, config.filter, config.dependencies)
            }
            case MATERIALIZE => {
              soda.materialize(config.viewUrlPath.get)
            }
            case INVALIDATE => {
              soda.invalidate(config.viewUrlPath.get)
            }
            case NEWDATA => {
              soda.newdata(config.viewUrlPath.get)
            }
            case COMMANDS => {
              soda.commands(config.status, config.filter)
            }
            case _ => {
              println("Unsupported Action: " + config.action.get.toString)
            }
          }
          println("\nRESULTS\n=======")
          println(CliFormat.serialize(res))
        } catch {
          case t: Throwable => println(s"\nERROR: ${t.getMessage}\n")
        }
      }
      case None => // usage information has already been displayed
    }
  }

}



