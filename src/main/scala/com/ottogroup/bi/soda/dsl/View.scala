package com.ottogroup.bi.soda.dsl

import scala.collection.mutable.LinkedHashMap
import scala.collection.mutable.ListBuffer
import scala.util.matching.Regex
import scala.collection.mutable.HashSet
import scala.collection.mutable.HashMap
import java.lang.reflect.Method
import collection.JavaConversions._
import com.openpojo.reflection.impl.PojoClassFactory
import scala.tools.nsc.Settings
import scala.tools.nsc.interpreter.IMain
import java.io.PrintWriter
import scala.tools.nsc.interpreter.NamedParam
import scala.tools.nsc.interpreter.NamedParamClass
import scala.tools.nsc.interpreter.NamedParam.Untyped
import scala.tools.nsc.interpreter.AbstractFileClassLoader
import java.net.URLClassLoader
import com.ottogroup.bi.soda.test.rows
import com.ottogroup.bi.soda.dsl.views.ViewUrlParser
import com.ottogroup.bi.soda.dsl.views.ViewUrlParser.ParsedView
import com.ottogroup.bi.soda.dsl.views.ViewUrlParser.ParsedViewAugmentor

abstract class View extends Structure with ViewDsl with DelayedInit {
  private def partitioningSuffix = {
    val partitionings = parameters
      .filter { p => isPartition(p) && isSuffixPartition(p) }
      .map { p => p.v.get }

    if (partitionings.isEmpty)
      ""
    else
      "_" + partitionings.mkString("_").toLowerCase()
  }

  override def n = super.n + partitioningSuffix

  def nWithoutPartitioningSuffix = super.n

  def module = Named.formatName(moduleNameBuilder()).replaceAll("[.]", "_")

  def getCanonicalClassname = this.getClass.getCanonicalName

  var moduleNameBuilder: () => String = () => this.getClass().getPackage().getName()
  var env = "dev"
  var dbNameBuilder: String => String = (env: String) => env.toLowerCase() + "_" + module
  var moduleLocationPathBuilder: String => String = (env: String) => ("_hdp_" + env.toLowerCase() + "_" + module.replaceFirst("app", "applications")).replaceAll("_", "/")
  var locationPathBuilder: String => String = (env: String) => moduleLocationPathBuilder(env) + (if (additionalStoragePathPrefix != null) "/" + additionalStoragePathPrefix else "") + "/" + n + (if (additionalStoragePathSuffix != null) "/" + additionalStoragePathSuffix else "")
  var partitionPathBuilder: () => String = () => partitionParameters.foldLeft("")((s, partition) => s + "/" + partition.n + "=" + partition.v.getOrElse(""))

  var avroSchemaPathPrefixBuilder: String => String = (env: String) => s"hdfs:///hdp/${env}/global/datadictionary/schema/avro"

  def dbName = dbNameBuilder(env)
  def tableName = dbName + "." + n
  def locationPath = locationPathBuilder(env)
  def fullPath = locationPath + partitionPathBuilder()
  def avroSchemaPathPrefix = avroSchemaPathPrefixBuilder(env)

  private val suffixPartitions = new HashSet[Parameter[_]]()

  def isPartition(p: Parameter[_]) = parameters.contains(p)

  def isSuffixPartition(p: Parameter[_]) = suffixPartitions.contains(p)

  def asTableSuffix[P <: Parameter[_]](p: P): P = {
    suffixPartitions.add(p)
    p
  }

  def parameters = this.getClass().getMethods()
    .filter { _.getParameterTypes().length == 0 }
    .filter { !_.getName().contains("$") }
    .filter { m => classOf[Parameter[_]].isAssignableFrom(m.getReturnType()) }
    .map { m => m.invoke(this).asInstanceOf[Parameter[_]] }
    .filter { m => m != null }
    .sortWith { _.orderWeight < _.orderWeight }
    .toSeq

  def partitionParameters = parameters
    .filter { p => isPartition(p) && !isSuffixPartition(p) }

  private val dependencyFutures = ListBuffer[() => Seq[View]]()

  def dependencies = dependencyFutures.flatMap { _() }.distinct

  def dependsOn[V <: View: Manifest](dsf: () => Seq[V]) {
    val df = () => dsf().map { View.register(this.env, _) }

    dependencyFutures += df
  }

  def dependsOn[V <: View: Manifest](df: () => V) = {
    val dsf = () => List(View.register(this.env, df()))

    dependsOn(dsf)

    () => dsf().head
  }

  var storageFormat: StorageFormat = TextFile()
  var additionalStoragePathPrefix: String = null
  var additionalStoragePathSuffix: String = null

  def storedAs(f: StorageFormat, additionalStoragePathPrefix: String = null, additionalStoragePathSuffix: String = null) {
    storageFormat = f
    this.additionalStoragePathPrefix = additionalStoragePathPrefix
    this.additionalStoragePathSuffix = additionalStoragePathSuffix
  }

  var comment: Option[String] = None

  def comment(aComment: String) {
    comment = Some(aComment)
  }

  var transformation: () => Transformation = () => NoOp()

  def transformVia(ft: () => Transformation) {
    initViewReferences
    transformation = ft
  }

  def initViewReferences() {
    for (p <- parameters)
      p.structure = this
  }

  def delayedInit(body: => Unit) {
    body
    initViewReferences

  }

  def configureTransformation(k: String, v: Any) {
    val t = transformation()
    transformVia(() => t.configureWith(Map((k, v))))
  }

  def configureTransformation(c: Map[String, Any]) {
    val t = transformation()
    transformVia(() => t.configureWith(c))
  }
}

object View {
  private val knownViews = HashMap[View, View]()

  def register[V <: View: Manifest](env: String, v: V): V = this.synchronized {
    val registeredView = knownViews.get(v) match {
      case Some(registeredView) => {
        registeredView.asInstanceOf[V]
      }
      case None => {
        knownViews.put(v, v)
        v
      }
    }
    registeredView.env = env
    registeredView
  }

  case class TypedAny(v: Any, t: Manifest[_])
  implicit def t[V: Manifest](v: V) = TypedAny(v, manifest[V])

  def viewsInPackage(packageName: String): Seq[Class[View]] = {
    PojoClassFactory.getPojoClassesRecursively(packageName, null).filter { _.extendz(classOf[View]) }.filter { !_.extendz(classOf[rows]) }.filter { !_.isAbstract() }.map { _.getClazz() }.toSeq.asInstanceOf[Seq[Class[View]]]
  }

  def getTraits[V <: View: Manifest](viewClass: Class[V]) = {
    viewClass.getInterfaces().filter(_ != classOf[Serializable]).filter(_ != classOf[scala.Product])
  }

  def newView[V <: View: Manifest](viewClass: Class[V], env: String, parameterValues: TypedAny*): V = {
    val viewCompanionObjectClass = Class.forName(viewClass.getName() + "$")
    val viewCompanionConstructor = viewCompanionObjectClass.getDeclaredConstructor()
    viewCompanionConstructor.setAccessible(true)
    val viewCompanionObject = viewCompanionConstructor.newInstance()

    val applyMethods = viewCompanionObjectClass.getDeclaredMethods()
      .filter { _.getName() == "apply" }

    val viewConstructor = applyMethods
      .filter { apply =>
        val parameterTypes = apply.getGenericParameterTypes().distinct
        !((parameterTypes.length == 1) && (parameterTypes.head == classOf[Object]))
      }
      .head

    val parametersToPass = ListBuffer[Any]()
    val parameterValuesPassed = ListBuffer[TypedAny]()
    parameterValuesPassed ++= parameterValues

    for (constructorParameterType <- viewConstructor.getParameterTypes()) {
      var passedValueForParameter: TypedAny = null

      for (parameterValue <- parameterValuesPassed; if passedValueForParameter == null) {
        if (constructorParameterType.isAssignableFrom(parameterValue.t.erasure)) {
          passedValueForParameter = parameterValue
        }
      }

      if (passedValueForParameter != null) {
        parameterValuesPassed -= passedValueForParameter
      }

      parametersToPass += passedValueForParameter.v
    }

    register(env, viewConstructor.invoke(viewCompanionObject, parametersToPass.asInstanceOf[Seq[Object]]: _*).asInstanceOf[V])
  }

  def viewsFromUrl(viewUrlPath: String, parsedViewAugmentor: ParsedViewAugmentor = new ParsedViewAugmentor() {}): List[View] = ViewUrlParser
    .parse(viewUrlPath)
    .map { parsedViewAugmentor.augment(_) }
    .filter { _ != null }
    .map { case ParsedView(env, viewClass, parameters) => newView(viewClass, env, parameters: _*) }

}