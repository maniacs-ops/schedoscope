/**
  * Copyright 2015 Otto (GmbH & Co KG)
  *
  * Licensed under the Apache License, Version 2.0 (the "License");
  * you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */
package org.schedoscope.test.resources

import java.io.File
import java.net.{InetAddress, URL, URLClassLoader}
import java.nio.file.{Files, Paths}
import java.util.Properties

import net.lingala.zip4j.core.ZipFile
import org.apache.commons.io.FileUtils
import org.apache.derby.drda.NetworkServerControl
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, FileUtil, Path}
import org.apache.hadoop.hive.conf.HiveConf
import org.apache.hadoop.hive.conf.HiveConf.ConfVars._

import scala.Array.canBuildFrom

class LocalTestResources extends TestResources {
  setupLocalHadoop()

  override lazy val hiveConf: HiveConf = {
    // we don't directly instantiate a new HiveConf(), because then hive-site.xml
    // would be loaded from classpath too early (we must make sure to write 
    // hive-site.xml BEFORE it is loaded the first time)
    val conf = new Properties()
    conf.put(METASTOREWAREHOUSE.toString, hiveWarehouseDir)
    conf.put(LOCALMODEAUTO.toString, "true")
    conf.put(METASTORECONNECTURLKEY.toString, metastoreUri + ";create=true")
    conf.put(HIVEAUXJARS.toString, compiledClassesPath())
    conf.put(LOCALMODEMAXINPUTFILES.toString, "20")
    conf.put(LOCALMODEMAXBYTES.toString, "1342177280L")
    conf.put(SCRATCHDIR.toString, hiveScratchDir)
    conf.put(SUBMITLOCALTASKVIACHILD.toString, "false")
    //conf.put(PLAN_SERIALIZATION.toString(), "javaXML")
    //conf.put(HIVE_LOG_INCREMENTAL_PLAN_PROGRESS_INTERVAL.toString(), "60000")
    val props = conf.stringPropertyNames().toArray().map(p => s"<property><name>${p.toString}</name><value>${conf.getProperty(p.toString)}</value></property>").mkString("\n")
    Files.write(Paths.get(hiveSiteXmlPath.get), ("<configuration>\n" + props + "\n</configuration>").getBytes());
    new HiveConf()
  }

  override lazy val hiveWarehouseDir: String = {
    val dir = Paths.get("target/hive-warehouse").toAbsolutePath()
    if (Files.exists(dir)) {
      FileUtils.deleteDirectory(dir.toFile())
    }
    val d = Files.createDirectory(dir).toString.replaceAll("\\\\", "/")

    new Path("file:///", d).toString()
  }

  override lazy val hiveScratchDir: String = {
    val dir = Paths.get("target/hive-scratch").toAbsolutePath()

    if (Files.exists(dir)) {
      FileUtils.deleteDirectory(dir.toFile())
    }

    val dirUrl = "file:///" + dir.toString.replaceAll("\\\\", "/")

    val f = new File(dirUrl)
    f.mkdir()
    f.setExecutable(true, false)
    f.setWritable(true, false)
    f.setReadable(true, false)

    new Path(dirUrl).toString()
  }

  override val hiveSiteXmlPath = Some("target/test-classes/hive-site.xml")

  override lazy val metastoreUri = LocalTestResources.derbyUri + "metastore_db" //"jdbc:derby:memory:metastore_db"

  override lazy val fileSystem: FileSystem = FileSystem.getLocal(new Configuration())

  override val jdbcUrl = "jdbc:hive2://"

  override lazy val remoteTestDirectory: String = new Path("file:///", Paths.get("target").toAbsolutePath().toString).toString

  override val namenode = "file:///"

  val dependenciesDir = "deploy/dependencies"

  def compiledClassesPath() = {
    val classPathMembers = this.getClass.getClassLoader.asInstanceOf[URLClassLoader].getURLs.map {
      _.toString()
    }.distinct
    val nonJarClassPathMembers = classPathMembers.filter {
      !_.endsWith(".jar")
    }.toList
    nonJarClassPathMembers.map(_.replaceAll("file:", "")).mkString(",")
  }

  def setupLocalHadoop() {
    if (System.getenv("HADOOP_HOME") == null) {
      throw new RuntimeException("HADOOP_HOME must be set!")
    }
    val classPathMembers = this.getClass.getClassLoader.asInstanceOf[URLClassLoader].getURLs.map {
      _.toString()
    }.distinct
    val jarClassPathMembers = classPathMembers.filter {
      _.endsWith(".jar")
    }.toList
    val launcherJars = jarClassPathMembers.filter {
      _.contains("hadoop-launcher")
    }
    val hadoopHome = new File(System.getenv("HADOOP_HOME"))

    if (launcherJars.size == 1 && !hadoopHome.exists) {
      hadoopHome.mkdirs
      new ZipFile(launcherJars.head.replaceAll("file:", "")).extractAll(hadoopHome.toString)
      FileUtil.chmod(hadoopHome.toString, "777", true)
    }

    val hadoopLibDir = new File(hadoopHome.toString() + File.separator + "lib")
    if (hadoopLibDir.exists)
      FileUtils.deleteDirectory(hadoopLibDir)
    hadoopLibDir.mkdir

    val jarCopyOperations = jarClassPathMembers
      .filter {
        !_.contains("slf4j-log4j12")
      }
      .filter {
        !_.contains("slf4j-simple")
      }
      .filter {
        !_.contains("0.13.1")
      }
      .foldLeft(List[(File, File)]()) {
        case (jarCopies, jarFile) =>
          ((new File(new URL(jarFile).toURI()),
            new File(new Path(System.getenv("HADOOP_HOME"), "lib" + File.separator + new Path(jarFile).getName).toString)) :: jarCopies)
      }

    jarCopyOperations.foreach {
      case (source, target) => {
        FileUtils.copyFile(source, target)
      }
    }
  }
}

/**
  * In order to support testing of transformations that run in different JVMs we need the derby metastore database
  * accessible via a socket and not just in-memory.
  *
  * So this is a singleton managing the derby instance.
  */

object LocalTestResources {

  /**
    * The derby server control object
    */
  lazy val derbyServer = {

    FileUtils.deleteDirectory(new File("metastore_db"))
    FileUtils.deleteQuietly(new File("derby.log"))

    var networkServerControl: NetworkServerControl = null
    var port = 15270
    var notStarted = true
    var acceptsConnections = false

    while (notStarted) {
      try {
        networkServerControl = new NetworkServerControl(InetAddress.getByName("127.0.0.1"), port)
        networkServerControl.start(null)
        notStarted = false
      } catch {
        case _: Throwable => port += 1
      }
    }

    while (!acceptsConnections) {
      try {
        networkServerControl.ping()
        acceptsConnections = true
      } catch {
        case _: Throwable => Thread.sleep(10)
      }
    }


    networkServerControl
  }

  /**
    * The JDBC uri to connect to our derby instance.
    */
  lazy val derbyUri = s"jdbc:derby://${derbyServer.getCurrentProperties().getProperty("derby.drda.host")}:${derbyServer.getCurrentProperties().getProperty("derby.drda.portNumber")}/"
}