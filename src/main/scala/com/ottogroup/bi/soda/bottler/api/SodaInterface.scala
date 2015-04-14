package com.ottogroup.bi.soda.bottler.api

trait SodaInterface {

  def materialize(viewUrlPath: String): SodaCommandStatus

  def invalidate(viewUrlPath: String): SodaCommandStatus

  def newdata(viewUrlPath: String): SodaCommandStatus

  def commandStatus(commandId: String): SodaCommandStatus

  def commands(status: Option[String], filter: Option[String]): List[SodaCommandStatus]

  def views(viewUrlPath: Option[String], status: Option[String], filter: Option[String], dependencies: Option[Boolean]): ViewStatusList

  def actions(status: Option[String], filter: Option[String]): ActionStatusList

  // def queues(typ: Option[String], filter: Option[String]) : QueueStatus FIXME: separate queues from actions

  def shutdown()
}