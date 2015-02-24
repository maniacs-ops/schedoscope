package com.ottogroup.bi.soda.bottler

import akka.actor.Actor
import akka.actor.Props
import akka.routing.BroadcastRouter
import akka.actor.ActorRef
import com.ottogroup.bi.soda.dsl.transformations.sql.HiveTransformation
import com.ottogroup.bi.soda.dsl.transformations.filesystem.FilesystemTransformation
import org.apache.hadoop.conf.Configuration
import akka.pattern.{ ask, pipe }
import scala.concurrent.duration.Duration
import akka.util.Timeout
import akka.event.LoggingReceive
import akka.event.Logging
import com.ottogroup.bi.soda.dsl.transformations.oozie.OozieTransformation
import com.ottogroup.bi.soda.dsl.View
import com.ottogroup.bi.soda.dsl.transformations.filesystem.CopyFrom
import com.ottogroup.bi.soda.dsl.transformations.filesystem.Copy
import akka.contrib.pattern.Aggregator
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._
import com.ottogroup.bi.soda.bottler.api.Settings
import collection.JavaConversions._
import com.typesafe.config.Config
import com.ottogroup.bi.soda.bottler.api.Settings
import com.ottogroup.bi.soda.bottler.api.SettingsImpl
import com.typesafe.config.ConfigObject
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValueFactory
import com.ottogroup.bi.soda.dsl.Transformation
import com.ottogroup.bi.soda.bottler.api.DriverSettings

/**
 * This actor aggregrates responses from multiple Actors
 * Used for retrieving running jobs,
 *
 * @author dev_hzorn
 *
 */
class StatusRetriever extends Actor with Aggregator {

  expectOnce {
    case GetProcessList(s) => new MultipleResponseHandler(s, "")
  }

  class MultipleResponseHandler(originalSender: ActorRef, propName: String) {

    import context.dispatcher
    import collection.mutable.ArrayBuffer

    val values = ArrayBuffer.empty[ActionStatusResponse]

    context.actorSelection("/user/actions/*") ! GetStatus()
    context.system.scheduler.scheduleOnce(50 milliseconds, self, TimedOut)

    val handle = expect {
      case ar: ActionStatusResponse => values += ar
      case TimedOut => processFinal(values.toList)
    }

    def processFinal(eval: List[ActionStatusResponse]) {
      unexpect(handle)
      originalSender ! ProcessList(eval)
      context.stop(self)
    }
  }
}

object ActionFactory {
  def createActor(name: String, conf: Config) = {
    name match {
      case "hive" => HiveActor.props(new DriverSettings(conf, name))
      case "oozie" => OozieActor.props(new DriverSettings(conf, name))
      case "file" => FileSystemActor.props(new DriverSettings(conf, name))
    }
  }
  def getTransformationTypeName(t: Transformation) =
    t match {
      case _: OozieTransformation => "oozie"
      case _: HiveTransformation => "hive"
      case _: FilesystemTransformation => "file"
    }
}

/**
 * Supervisor for Hive, Oozie, Routers
 * Implements a pull-work-pattern that does not fill the mailboxes of actors.
 * This way, a long running job will not block short-running
 * In future we should learn runtimes of jobs and distribute to dedicated queues.
 */
class ActionsRouterActor(conf: Configuration) extends Actor {
  import context._
  val log = Logging(system, this)
  val settings = Settings.get(system)

  val queues =
    settings.availableTransformations.entrySet().foldLeft(Map[String, collection.mutable.Queue[CommandWithSender]]()) {
      (map, entry) =>
        {
          map + (entry.getKey() ->
            new collection.mutable.Queue[CommandWithSender]())

        }
    }
  val routers = settings.availableTransformations.entrySet().foldLeft(Map[String, ActorRef]()) {
    (map, entry) =>
      {
        val conf = entry.getValue().asInstanceOf[ConfigObject].toConfig().withFallback(ConfigFactory.empty.withValue("concurrency", ConfigValueFactory.fromAnyRef(1)))
        map + (entry.getKey() ->
          actorOf(ActionFactory.createActor(entry.getKey(), conf).withRouter(BroadcastRouter(nrOfInstances = conf.getInt("concurrency")))))

      }
  }

  def receive = LoggingReceive({
    case PollCommand(typ) =>
      queues.get(typ).map(q => if (!q.isEmpty) sender ! q.dequeue)
    case view: View => view.transformation() match {
      case cmd: OozieTransformation => {
        queues.get("oozie").get.enqueue(CommandWithSender(cmd, sender))
        routers.get("oozie").get ! WorkAvailable
      }
      case cmd: HiveTransformation => {
        queues.get("hive").get.enqueue(CommandWithSender(cmd, sender))
        routers.get("hive").get ! WorkAvailable
      }
      case cmd: CopyFrom => routers.get("file").get ! CommandWithSender(Copy(cmd.fromPattern, view.partitionPathBuilder()), sender)
      case cmd: FilesystemTransformation => {
        queues.get("file").get.enqueue(CommandWithSender(cmd, sender))
        routers.get("file").get ! WorkAvailable

      }
    }
    case cmd: OozieTransformation => {
      queues.get("oozie").get.enqueue(CommandWithSender(cmd, sender))
      routers.get("oozie").get ! WorkAvailable
    }
    case cmd: HiveTransformation => {
      queues.get("hive").get.enqueue(CommandWithSender(cmd, sender))
      routers.get("hive").get ! WorkAvailable
    }

    case cmd: FilesystemTransformation => {
      queues.get("file").get.enqueue(CommandWithSender(cmd, sender))
      routers.get("file").get ! WorkAvailable
    }
    case cmd: GetStatus => {
      implicit val timeout = Timeout(600);
      actorOf(Props(new StatusRetriever)) ! GetProcessList(sender())
    }
    case cmd: Deploy => {
      routers.map(el => {
        val name = el._1
        val act = el._2
        queues.get(name).get.enqueue(CommandWithSender(cmd, sender))
        act ! WorkAvailable
      })
    }
  })
}

object ActionsRouterActor {
  def props(conf: Configuration) = Props(new ActionsRouterActor(conf))
}