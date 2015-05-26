package com.ottogroup.bi.soda.bottler

import com.ottogroup.bi.soda.dsl.View
import java.util.Properties
import akka.actor.ActorRef
import org.joda.time.LocalDate
import org.joda.time.LocalDateTime


object ProcessStatus extends Enumeration {
    type ProcessStatus = Value
    val RUNNING,IDLE,ERROR,STOPPED = Value
}

class MessageType
class ErrorMessage extends MessageType

sealed class Success
sealed class Failure
class Command
case class Error(view: View, reason: String) extends Failure
case class FatalError(view: View, reason: String) extends Failure
case class OozieException(e: Throwable) extends Failure
case class Exception(e: Throwable) extends Failure
case class HiveError() extends Failure
case class OozieError() extends Failure
case class OozieSuccess() extends Success
case class HiveSuccess() extends Success
case class ViewMaterialized(view: View) extends Success
case class ViewMaterializedIncomplete(view: View) extends Success

case class NoDataAvaiable(view: View) extends Success

case class NewDataAvailable(view: View) extends Command

case class HiveCommand(sql: String) extends Command
case class OozieCommand(properties: Properties) extends Command
case class GetStatus() extends Command
case class ViewStatus(view: View, status: String, dependencies: Seq[ViewStatus]) extends Success
sealed abstract class ActionStatusResponse
import ProcessStatus._
case class HiveStatusResponse(message: String, actor: ActorRef, status:ProcessStatus,query:String,start:LocalDateTime) extends ActionStatusResponse
case class OozieStatusResponse(message: String, actor: ActorRef, status:ProcessStatus,jobId:String,start:LocalDateTime) extends ActionStatusResponse
case class KillAction() extends Command
case class Suspend() extends Command
case class InternalError(message: String) extends Failure
