package services

import akka.actor._
import pdi.jwt._
import scala.util._
import com.softwaremill.tagging._
import akka.http.scaladsl.model.Uri
import akka.util.Timeout
import scala.concurrent.duration._
import domain.InstanceAggregate
import domain.InstanceAggregate.RecordInstanceStart
import akka.event.LoggingReceive
import utils.IdUtils
import domain.SchedulerAggregate

object InstanceAggregateManager {

  sealed trait Command
  case class StartInstance(clusterId: String, image: String) extends Command
  case class VerifyJwt(token: String) extends Command
  case class InstanceEnvelope(instanceId: String, msg: Any) extends Command

  sealed trait Response
  case class InstanceStarted(instanceId: String) extends Response

}

class InstanceAggregateManager(
    val clusterAggregateManager: ActorRef @@ ClusterSharder.type)
    extends Actor with ActorLogging {
  import InstanceAggregateManager._
  import domain.ClusterAggregate
  import akka.pattern.{ask, pipe}
  import context.dispatcher

  val receive: Receive = LoggingReceive {
    case StartInstance(clusterId, image) =>
      implicit val timeout = Timeout(1.minute)
      val requester = sender
      var instanceId = IdUtils.timePrefix+IdUtils.randomId(16)
      (instanceRef(instanceId) ? RecordInstanceStart(clusterId)).map {
        case InstanceAggregate.Ack =>
          (clusterAggregateManager ? ClusterSharder.Envelope(
              clusterId,
              ClusterAggregate.StartInstance(instanceId, image))).map {
            case SchedulerAggregate.Ack =>
              InstanceStarted(instanceId)
          }.pipeTo(requester)
      }
    case VerifyJwt(token) =>
      resolveJwtInstance(token) match {
        case Right(ref) =>
          ref forward InstanceAggregate.VerifyJwt(token)
        case Left(errorMsg) =>
          sender ! InstanceAggregate.InvalidJwt(errorMsg)
      }
    case InstanceEnvelope(instanceId, msg) =>
      instanceRef(instanceId) forward msg
  }

  def instanceRef(instanceId: String) = {
    context.child(instanceId).getOrElse {
      val agg = context.actorOf(
          aggregateProps(instanceId), instanceId)
      context.watch(agg)
      agg
    }
  }

  private def aggregateProps(instanceId: String): Props =
    Props(classOf[InstanceAggregate], clusterAggregateManager)

  private def resolveJwtInstance(token: String): Either[String, ActorRef] =
    JwtJson.decode(token, JwtOptions(signature=false))
        .toOption.toRight("Unable to decode token")
        .right.flatMap { claim =>
          val issuerPrefix = "instance-"
          claim.issuer match {
            case Some(id) if id.startsWith(issuerPrefix) =>
              Right(instanceRef(id.stripPrefix(issuerPrefix)))
            case Some(id) =>
              Left("Invalid issuer format")
            case None =>
              Left("No issuer defined")
          }
        }
}