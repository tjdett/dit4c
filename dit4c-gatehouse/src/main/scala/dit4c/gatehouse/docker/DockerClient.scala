package dit4c.gatehouse.docker

import scala.collection.JavaConversions._
import scala.concurrent.Future
import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.util.Timeout
import akka.event.Logging
import spray.json.DefaultJsonProtocol
import spray.json.RootJsonFormat
import spray.json.JsObject
import java.net.URI
import com.github.dockerjava.core.DockerClientConfig
import com.github.dockerjava.core.DockerClientBuilder
import com.github.dockerjava.api.model.InternetProtocol
import scala.util.Try
import scala.concurrent.ExecutionContext
import java.util.concurrent.Executors
import com.github.dockerjava.api.model.Event
import com.github.dockerjava.api.async.ResultCallback
import scala.concurrent.Promise
import java.io.Closeable

class DockerClient(val dockerClientConfig: DockerClientConfig) {
  implicit val system: ActorSystem = ActorSystem()
  implicit val timeout: Timeout = Timeout(15.seconds)
  import system.dispatcher // implicit execution context

  val cpec = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(10))
  val log = Logging(system, getClass)

  val POTENTIAL_SERVICE_PORTS = Seq(80, 8080, 8888)

  import DockerClient.ContainerEvent
  val NOTABLE_CONTAINER_EVENTS = Set("die", "rename", "start", "stop")

  val dockerClient = DockerClientBuilder.getInstance(dockerClientConfig).build

  def events(callback: (ContainerEvent) => Unit): (Future[Closeable], Future[Unit]) =
    dockerClient.eventsCmd.exec(new ResultCallback[Event]() {
      protected val pStart = Promise[Closeable]()
      protected val pClose = Promise[Unit]()
      def futures = (pStart.future, pClose.future)
      def onStart(closeable: Closeable) { pStart.success(closeable) }
      /** Called when an async result event occurs */
      def onNext(obj: Event) {
        if (NOTABLE_CONTAINER_EVENTS.contains(obj.getStatus)) {
          callback(ContainerEvent(obj.getId, obj.getStatus))
        }
      }
      /** Called when an exception occurs while processing */
      def onError(throwable: Throwable) {
        throwable match {
          // Expected on forced close
          case e: java.io.IOException => pClose.success(())
          case e: Throwable => pClose.failure(throwable)
        }
      }
      /** Called when processing was finished either by reaching the end or
       *  by aborting it */
      def onComplete() { pClose.success(()) }
      def close() {}
    }).futures

  def containerPort(containerId: String): Option[String] =
    try {
      val info = dockerClient.inspectContainerCmd(containerId).exec
      val exposedPorts = Try(info.getConfig.getExposedPorts)
        .getOrElse(Array.empty).toSet
        .filter(_.getProtocol == InternetProtocol.TCP)
        .map(_.getPort)
      POTENTIAL_SERVICE_PORTS
        .filter(exposedPorts.contains)
        .headOption
        .map(info.getNetworkSettings.getIpAddress+":"+_)
    } catch {
        case e: com.github.dockerjava.api.exception.NotFoundException => None
    }

  def containerPorts =
    Future(dockerClient.listContainersCmd.exec.toSeq).map { containers =>
      val nameMap =
        containers.flatMap { c =>
          c.getNames.toSeq
            .map(_.stripPrefix("/"))
            .map((_, c.getId))
        }.filter(_._1.isValidContainerName).toMap
      // Future id => port map
      val portMap = {
          // Futures for each container
          nameMap.values.toSet.map { id: String =>
            // Get container port, wrapping name into the Option response
            containerPort(id).map { (id, _) }
          }
        }.flatten.toMap // Turn into map, removing None lookups
      // Merge the names and ports
      nameMap
        .filter { case (name, id) => portMap.contains(id) }
        .map { case (name, id) =>
          (name, portMap(id))
        }
    }

  implicit class ContainerNameTester(str: String) {

    // Same as domain name, but use of capitals is prohibited because container
    // names are case-sensitive while host names should be case-insensitive.
    def isValidContainerName = {
      !str.isEmpty &&
      str.length <= 63 &&
      !str.startsWith("-") &&
      !str.endsWith("-") &&
      str.matches("[a-z0-9\\-]+")
    }

  }

}

object DockerClient {

  case class ContainerEvent(containerId: String, eventType: String)

  def apply(uri: java.net.URI): DockerClient = apply(Some(uri))

  def apply(maybeUri: Option[java.net.URI]): DockerClient = new DockerClient(
    maybeUri.foldLeft(DockerClientConfig.createDefaultConfigBuilder)((b,uri) =>
      b.withUri(uri.toASCIIString)
    ).build)

}
