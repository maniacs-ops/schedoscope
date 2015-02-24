package com.ottogroup.bi.soda.bottler.driver
import com.ottogroup.bi.soda.dsl.transformations.sql.HiveTransformation
import com.ottogroup.bi.soda.dsl.transformations.sql.HiveTransformation._
import util.control.Breaks._
import org.apache.hadoop.security.UserGroupInformation
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.FileSystem
import org.apache.hadoop.fs.Path
import java.security.PrivilegedAction
import java.sql.Connection
import java.sql.DriverManager
import org.apache.commons.lang.StringUtils
import scala.collection.mutable.MutableList
import scala.collection.mutable.Queue
import scala.collection.mutable.Stack
import com.ottogroup.bi.soda.dsl.Transformation
import com.typesafe.config.Config
import com.ottogroup.bi.soda.bottler.api.Settings
import com.ottogroup.bi.soda.bottler.api.DriverSettings
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient
import org.apache.hadoop.hive.conf.HiveConf
import org.apache.hadoop.hive.metastore.api.Function
import collection.JavaConversions._
import org.apache.hadoop.hive.metastore.api.NoSuchObjectException
import org.apache.hadoop.hive.metastore.api.MetaException

class HiveDriver(val connection: Connection, val metastoreClient: HiveMetaStoreClient) extends Driver {

  override def run(t: Transformation): String = {
    t match {
      case th: HiveTransformation => {
        th.functionDefs.foreach( func => this.registerFunction(func) )
        th.sql.map(sql => replaceParameters(sql, th.configuration.toMap))
          .map(sql => if (!this.executeHiveQuery(sql)) return "")
      case _ => throw new RuntimeException("HiveDriver can only run HiveQl transformations.")
    }
    ""
  }

  override def runAndWait(t: Transformation): Boolean = {
    t match {
      case th: HiveTransformation => {
        th.functionDefs.foreach(func => this.registerFunction(func))
        th.sql.map(sql => replaceParameters(sql, th.configuration.toMap))
          .map(sql => if (!this.executeHiveQuery(sql)) return false)
      }
      case _ => throw new RuntimeException("HiveDriver can only run HiveQl transformations.")
    }
    true
  }

  def executeHiveQuery(sql: String): Boolean = {
    if (sql == null)
      return false

    // we mimic here the simple sql query splitting from
    // org.apache.hadoop.hive.cli.CliDriver#processLine    
    println(sql)
    val queries = Stack[String]("")
    sql.split(";").map(el => {
      if (StringUtils.endsWith(queries.head, "\\")) {
        queries.push(StringUtils.chop(queries.pop()) + ";" + el)
      } else {
        queries.push(el)
      }
    })
    queries.reverse.filter(q => !StringUtils.isBlank(q))
      .map(q => {
        val stmt = connection.createStatement()
        stmt.execute(q)
      })
    true
  }

  def registerFunction(f: Function) {
    try {
      metastoreClient.getFunction(f.getDbName, f.getFunctionName)
    } catch {
      case nso: MetaException => {
        val resourceJars = f.getResourceUris.map(jar => s"JAR '${jar.getUri}'").mkString(", ")
        // we don't use the metastore client here to create functions because we don't want
        // to bother with function ownerships
        println(s"CREATE FUNCTION ${f.getDbName}.${f.getFunctionName} AS ${f.getClassName} USING ${resourceJars}")
        this.executeHiveQuery(s"CREATE FUNCTION ${f.getDbName}.${f.getFunctionName} AS '${f.getClassName}' USING ${resourceJars}")
      }
    }
  }

}

object HiveDriver {
  def apply(ds: DriverSettings) = {
    Class.forName("org.apache.hive.jdbc.HiveDriver")
    val ugi = Settings().userGroupInformation
    ugi.reloginFromTicketCache()
    val c =
      ugi.doAs(new PrivilegedAction[Connection]() {
        def run(): Connection = {
          DriverManager.getConnection(Settings().jdbcUrl)
        }
      })
    val conf = new HiveConf()
    conf.set("hive.metastore.local", "false");
    conf.setVar(HiveConf.ConfVars.METASTOREURIS, Settings().metastoreUri.trim());
    if (Settings().kerberosPrincipal != null) {
      conf.setBoolVar(HiveConf.ConfVars.METASTORE_USE_THRIFT_SASL,
        true);
      conf.setVar(HiveConf.ConfVars.METASTORE_KERBEROS_PRINCIPAL,
        Settings().kerberosPrincipal);
    }
    val metastoreClient = new HiveMetaStoreClient(conf)

    val hd = new HiveDriver(c, metastoreClient)
    hd.driverSettings = ds
    hd
  }
}

