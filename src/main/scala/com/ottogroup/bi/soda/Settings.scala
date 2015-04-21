package com.ottogroup.bi.soda

import java.net.URLClassLoader
import java.nio.file.Paths
import java.util.Calendar
import java.util.concurrent.TimeUnit
import scala.Array.canBuildFrom
import scala.collection.mutable.HashMap
import scala.concurrent.duration.Duration
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.security.UserGroupInformation
import org.apache.hadoop.yarn.conf.YarnConfiguration
import com.ottogroup.bi.soda.bottler.driver.FileSystemDriver.fileSystem
import com.ottogroup.bi.soda.dsl.Parameter.p
import com.ottogroup.bi.soda.dsl.Transformation
import com.ottogroup.bi.soda.dsl.views.DateParameterizationUtils
import com.ottogroup.bi.soda.dsl.views.ViewUrlParser.ParsedViewAugmentor
import com.typesafe.config.Config
import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import com.ottogroup.bi.soda.bottler.driver.Driver

class SettingsImpl(val config: Config) extends Extension {
  val system = Settings.actorSystem

  private val driverSettings: HashMap[String, DriverSettings] = HashMap[String, DriverSettings]()

  lazy val env = config.getString("soda.app.environment")

  lazy val earliestDay = {
    val conf = config.getString("soda.scheduler.earliestDay")
    val Array(year, month, day) = conf.split("-")
    DateParameterizationUtils.parametersToDay(p(year), p(month), p(day))
  }

  lazy val latestDay = {
    val conf = config.getString("soda.scheduler.latestDay")
    if (conf == "now") {
      val now = Calendar.getInstance()
      now.set(Calendar.HOUR_OF_DAY, 0)
      now.set(Calendar.MINUTE, 0)
      now.set(Calendar.SECOND, 0)
      now.set(Calendar.MILLISECOND, 0)
      now
    } else {
      val Array(year, month, day) = conf.split("-")
      DateParameterizationUtils.parametersToDay(p(year), p(month), p(day))
    }
  }

  lazy val webserviceTimeOut: Duration =
    Duration(config.getDuration("soda.webservice.timeout", TimeUnit.MILLISECONDS),
      TimeUnit.MILLISECONDS)

  lazy val port = config.getInt("soda.webservice.port")
  
  lazy val webResourcesDirectory = config.getString("soda.webservice.resourceDirectory")

  lazy val jdbcUrl = config.getString("soda.metastore.jdbcUrl")

  lazy val kerberosPrincipal = config.getString("soda.kerberos.principal")

  lazy val metastoreUri = config.getString("soda.metastore.metastoreUri")

  lazy val parsedViewAugmentorClass = config.getString("soda.app.parsedViewAugmentorClass")

  def viewAugmentor = Class.forName(parsedViewAugmentorClass).newInstance().asInstanceOf[ParsedViewAugmentor]

  lazy val availableTransformations = config.getObject("soda.transformations")

  lazy val hadoopConf = new Configuration(true)

  lazy val transformationVersioning = config.getBoolean("soda.versioning.transformations")

  lazy val jobTrackerOrResourceManager = {
    val yarnConf = new YarnConfiguration(hadoopConf)
    if (yarnConf.get("yarn.resourcemanager.address") == null)
      config.getString("soda.hadoop.resourceManager")
    else
      yarnConf.get("yarn.resourcemanager.address")
  }

  lazy val nameNode = if (hadoopConf.get("fs.defaultFS") == null)
    config.getString("soda.hadoop.nameNode")
  else
    hadoopConf.get("fs.defaultFS")

  lazy val filesystemTimeout = getDriverSettings("filesystem").timeout
  lazy val schemaTimeout = Duration.create(config.getDuration("soda.scheduler.timeouts.schema", TimeUnit.SECONDS), TimeUnit.SECONDS)
  lazy val statusListAggregationTimeout = Duration.create(config.getDuration("soda.scheduler.timeouts.statusListAggregation", TimeUnit.SECONDS), TimeUnit.SECONDS)
  lazy val viewManagerResponseTimeout = Duration.create(config.getDuration("soda.scheduler.timeouts.viewManagerResponse", TimeUnit.SECONDS), TimeUnit.SECONDS)
  lazy val completitionTimeout = Duration.create(config.getDuration("soda.scheduler.timeouts.completion", TimeUnit.SECONDS), TimeUnit.SECONDS)

  lazy val retries = config.getInt("soda.action.retry")

  lazy val metastoreConcurrency = config.getInt("soda.metastore.concurrency")
  lazy val metastoreWriteBatchSize = config.getInt("soda.metastore.writeBatchSize")
  lazy val metastoreReadBatchSize = config.getInt("soda.metastore.readBatchSize")
  
  lazy val userGroupInformation = {
    UserGroupInformation.setConfiguration(hadoopConf)
    val ugi = UserGroupInformation.getCurrentUser()
    ugi.setAuthenticationMethod(UserGroupInformation.AuthenticationMethod.KERBEROS)
    ugi.reloginFromKeytab();
    ugi
  }

  def getDriverSettings(d: Any with Driver[_]): DriverSettings = {
    getDriverSettings(d.transformationName)
  }

  def getDriverSettings[T <: Transformation](t: T): DriverSettings = {
    val name = t.getClass.getSimpleName.toLowerCase.replaceAll("transformation", "").replaceAll("\\$", "")
    getDriverSettings(name)
  }
  
  def getDriverSettings(n : String) : DriverSettings = {
    if (!driverSettings.contains(n)) {     
      val confName = "soda.transformations." + n
      driverSettings.put(n, new DriverSettings(config.getConfig(confName), n))
    }

    driverSettings(n)
  }

  def getTransformationSetting(typ: String, setting: String) = {
    val confName = s"soda.transformations.${typ}.transformation.${setting}"
    config.getString(confName)
  }

}

object Settings extends ExtensionId[SettingsImpl] with ExtensionIdProvider {
  val actorSystem = ActorSystem("soda")

  override def lookup = Settings

  override def createExtension(system: ExtendedActorSystem) =
    new SettingsImpl(system.settings.config)

  override def get(system: ActorSystem): SettingsImpl = super.get(system)

  def apply() = {
    super.apply(actorSystem)
  }
}

class DriverSettings(val config: Config, val name: String) {
  lazy val location = Settings().nameNode + config.getString("location")
  lazy val libDirectory = config.getString("libDirectory")
  lazy val concurrency = config.getInt("concurrency")
  lazy val unpack = config.getBoolean("unpack")
  lazy val url = config.getString("url")
  lazy val timeout = Duration.create(config.getDuration("timeout", TimeUnit.SECONDS), TimeUnit.SECONDS)

  lazy val libJars = {
    val fromLibDir = libDirectory
      .split(",")
      .toList
      .filter(!_.trim.equals(""))
      .map(p => { if (!p.endsWith("/")) s"file://${p.trim}/*" else s"file://${p.trim}*" })
      .flatMap(dir => {
        fileSystem(dir, Settings().hadoopConf).globStatus(new Path(dir))
          .map(stat => stat.getPath.toString)
      })

    val fromClasspath = this.getClass.getClassLoader
      .asInstanceOf[URLClassLoader]
      .getURLs
      .map(el => el.toString)
      .distinct
      .filter(_.endsWith(s"-${name}.jar"))
      .toList

    (fromLibDir ++ fromClasspath).toList
  }

  lazy val libJarsHdfs = {
    if (unpack)
      List[String]()
    else {
      libJars.map(lj => location + "/" + Paths.get(lj).getFileName.toString)
    }
  }
}

