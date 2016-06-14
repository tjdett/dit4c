package dit4c.scheduler.routes

import akka.http.scaladsl.server.Directives
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport
import dit4c.scheduler.service.ClusterAggregateManager
import akka.actor.ActorRef
import akka.http.scaladsl.server.Route
import dit4c.scheduler.domain.ClusterAggregate
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.StatusCodes
import akka.util.Timeout
import scala.concurrent.duration._
import scala.util.matching.Regex
import akka.http.scaladsl.server.PathMatcher
import java.security.interfaces.RSAPublicKey
import dit4c.scheduler.domain.RktNode
import akka.event.Logging
import pdi.jwt.JwtBase64

object ClusterRoutes {
  import play.api.libs.json._
  import play.api.libs.functional.syntax._

  implicit def writesRSAPublicKey: Writes[RSAPublicKey] = (
      (__ \ 'kty).write[String] and
      (__ \ 'e).write[String] and
      (__ \ 'n).write[String]
  )( (k: RSAPublicKey) => (
      "RSA",
      JwtBase64.encodeString(k.getPublicExponent.toByteArray),
      JwtBase64.encodeString(k.getModulus.toByteArray)) )

  implicit def readsAddRktNode: Reads[ClusterAggregate.AddRktNode] = (
      (__ \ 'host).read[String] and
      (__ \ 'port).read[Int] and
      (__ \ 'username).read[String]
  )((host: String, port: Int, username: String) =>
    ClusterAggregate.AddRktNode(host, port, username, "/var/lib/dit4c-rkt"))

  implicit def writesCluster: OWrites[ClusterAggregate.Cluster] = (
      (__ \ 'id).write[String] and
      (__ \ 'type).write[String]
  )(cluster => (cluster.id, cluster.`type`.toString))

  implicit def writesNodeConfig: OWrites[RktNode.NodeConfig] = (
      (__ \ 'id).write[String] and
      (__ \ 'host).write[String] and
      (__ \ 'port).write[Int] and
      (__ \ 'username).write[String] and
      (__ \ "client-key").write[RSAPublicKey] and
      (__ \ "host-key").write[RSAPublicKey]
  )(rktNode => (
      rktNode.id,
      rktNode.connectionDetails.host,
      rktNode.connectionDetails.port,
      rktNode.connectionDetails.username,
      rktNode.connectionDetails.clientKey.public,
      rktNode.connectionDetails.serverKey.public))

}

class ClusterRoutes(zoneAggregateManager: ActorRef) extends Directives
    with PlayJsonSupport {

  implicit val timeout = Timeout(10.seconds)
  import akka.pattern.ask
  import ClusterRoutes._
  import ClusterAggregateManager._
  import ClusterAggregate.{Uninitialized, Cluster}

  def routes = clusterInstanceRoutes

  val clusterInstanceRoutes =
    pathPrefix("clusters" / Segment) { id =>
      clusterRoute(id)
    }

  def clusterRoute(id: String): Route = {
    pathEndOrSingleSlash {
      get {
        onSuccess(zoneAggregateManager ? GetCluster(id)) {
          case Uninitialized => complete(StatusCodes.NotFound)
          case zone: ClusterAggregate.Cluster => complete(zone)
        }
      }
    } ~
    pathPrefix("nodes") {
      pathEndOrSingleSlash {
        post {
          entity(as[ClusterAggregate.AddRktNode]) { cmd =>
            onSuccess(zoneAggregateManager ? ClusterCommand(id, cmd)) {
              case Uninitialized => complete(StatusCodes.NotFound)
              case node: RktNode.NodeConfig =>
                complete((StatusCodes.Created, node))
            }
          }
        }
      }
    }
  }

  def nodeRoute(clusterId: String, nodeId: String): Route = ???

}