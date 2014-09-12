package dit4c.machineshop

import akka.util.Timeout
import java.util.concurrent.TimeUnit
import org.specs2.mutable.Specification
import spray.testkit.Specs2RouteTest
import spray.http._
import StatusCodes._
import spray.routing.HttpService
import dit4c.machineshop.docker.DockerClient
import dit4c.machineshop.docker.models._
import java.util.UUID
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import akka.actor.ActorRef
import dit4c.machineshop.auth.SignatureActor
import akka.testkit.TestProbe
import akka.testkit.TestActor

class ApiServiceSpec extends Specification with Specs2RouteTest with HttpService {
  implicit val actorRefFactory = system

  import spray.util.pimpFuture
  implicit val timeout = new Timeout(5, TimeUnit.SECONDS)

  def mockDockerClient = new DockerClient {
    var containerList: Seq[DockerContainer] = Nil

    case class MockDockerContainer(
        val id: String,
        val name: String,
        val image: String,
        val status: ContainerStatus = ContainerStatus.Stopped) extends DockerContainer {
      override def refresh = Future.successful({
        containerList.find(_.name == name).get
      })
      override def start = Future.successful({
        updateList(
            new MockDockerContainer(id, name, image, ContainerStatus.Running))
      })
      override def stop(timeout: Duration) = Future.successful({
        updateList(
            new MockDockerContainer(id, name, image, ContainerStatus.Stopped))
      })
      override def delete = Future.successful({
        containerList = containerList.filterNot(_ == this)
      })
      private def updateList(changed: DockerContainer): DockerContainer = {
        containerList = containerList.filterNot(_ == this) ++ Seq(changed)
        changed
      }
    }

    override val images = new DockerImages {
      override def list = ???
      override def pull(imageName: String, tagName: String) = ???
    }

    override val containers = new DockerContainers {
      override def create(name: String, image: DockerImage) = Future.successful({
        val newContainer =
          new MockDockerContainer(UUID.randomUUID.toString, name, image)
        containerList = containerList ++ Seq(newContainer)
        newContainer
      })

      override def list = Future.successful(containerList)
    }
  }

  def mockSignatureActor(response: SignatureActor.AuthResponse): ActorRef = {
    val probe = TestProbe()
    probe.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot =
        msg match {
          case SignatureActor.AuthCheck(_) =>
            sender ! response
            TestActor.KeepRunning
        }
    })
    probe.ref
  }

  val image = "dit4c/dit4c-container-ipython"

  def route(implicit client: DockerClient, signatureActor: Option[ActorRef] = None) =
    ApiService(client, signatureActor).route

  "ApiService" should {

    "return all containers for GET requests to /containers" in {
      import spray.json._
      import DefaultJsonProtocol._
      implicit val client = mockDockerClient
      Get("/containers") ~> route ~> check {
        contentType must_== ContentTypes.`application/json`
        val json = JsonParser(responseAs[String])
        json must beAnInstanceOf[JsArray]
        json.asInstanceOf[JsArray].elements must beEmpty
      }
      client.containers.create("foobar", image).await
      Get("/containers") ~> route ~> check {
        contentType must_== ContentTypes.`application/json`
        val json = JsonParser(responseAs[String])
        json must beAnInstanceOf[JsArray]
        json.asInstanceOf[JsArray].elements must haveSize(1)
        val container = json.asInstanceOf[JsArray].elements.head.asJsObject
        container.fields("name").convertTo[String]  must_== "foobar"
      }
    }

    "return containers for GET requests to /containers/:name" in {
      import spray.json._
      import DefaultJsonProtocol._
      implicit val client = mockDockerClient
      Get("/containers/foobar") ~> route ~> check {
        status must_== StatusCodes.NotFound
      }
      client.containers.create("foobar", image).await
      Get("/containers/foobar") ~> route ~> check {
        contentType must_== ContentTypes.`application/json`
        val json = JsonParser(responseAs[String]).convertTo[JsObject]
        json.fields("name").convertTo[String] must_== "foobar"
      }
    }

    "create containers with POST requests to /containers/new" in {
      import spray.json._
      import spray.httpx.SprayJsonSupport._
      import DefaultJsonProtocol._
      implicit val client = mockDockerClient
      client.containers.list.await must beEmpty
      val requestJson = JsObject(
          "name" -> JsString("foobar"),
          "image" -> JsString(image))
      Post("/containers/new", requestJson) ~> route ~> check {
        contentType must_== ContentTypes.`application/json`
        val json = JsonParser(responseAs[String]).convertTo[JsObject]
        json.fields("name").convertTo[String] must_== "foobar"
      }
      client.containers.list.await must haveSize(1)
    }

    "require HTTP Signatures for POST requests to /containers/new" in {
      import spray.json._
      import spray.httpx.SprayJsonSupport._
      import DefaultJsonProtocol._
      val client = mockDockerClient
      client.containers.list.await must beEmpty
      val requestJson = JsObject(
          "name" -> JsString("foobar"),
          "image" -> JsString(image))
      // Check a challenge is issued
      Post("/containers/new", requestJson) ~>
          route(client, Some(mockSignatureActor(
              SignatureActor.AccessDenied("Just 'cause")))) ~>
          check {
        status must_== StatusCodes.Unauthorized
        header("WWW-Authenticate") must beSome
      }
      client.containers.list.await must beEmpty
      // Create a Authorization header
      val authHeader = HttpHeaders.Authorization(
              GenericHttpCredentials("Signature", "",
                  Map(/* Parameters don't matter - using mock */ )))
      // Check that signature failure works
      Post("/containers/new", requestJson) ~>
          addHeader(authHeader) ~>
          route(client, Some(mockSignatureActor(
              SignatureActor.AccessDenied("Just 'cause")))) ~>
          check {
        status must_== StatusCodes.Forbidden
      }
      client.containers.list.await must beEmpty
      Post("/containers/new", requestJson) ~>
          addHeader(authHeader) ~>
          route(client, Some(mockSignatureActor(
              SignatureActor.AccessGranted))) ~>
          check {
        contentType must_== ContentTypes.`application/json`
        val json = JsonParser(responseAs[String]).convertTo[JsObject]
        json.fields("name").convertTo[String] must_== "foobar"
      }
      client.containers.list.await must haveSize(1)
    }

    "delete containers with DELETE requests to /containers/:name" in {
      import spray.json._
      import spray.httpx.SprayJsonSupport._
      import DefaultJsonProtocol._
      implicit val client = mockDockerClient
      val dc = client.containers.create("foobar", image).await
      client.containers.list.await must haveSize(1)
      Delete("/containers/foobar") ~> route ~> check {
        status must_== StatusCodes.NoContent
      }
      client.containers.list.await must beEmpty
    }

    "start containers with POST requests to /containers/:name/start" in {
      import spray.json._
      import spray.httpx.SprayJsonSupport._
      import DefaultJsonProtocol._
      implicit val client = mockDockerClient
      val dc = client.containers.create("foobar", image).await
      dc.isRunning must beFalse
      Post("/containers/foobar/start") ~> route ~> check {
        contentType must_== ContentTypes.`application/json`
        responseAs[JsObject].fields("active") must_== JsBoolean(true)
      }
      dc.refresh.await.isRunning must_== true
    }

    "stop containers with POST requests to /containers/:name/stop" in {
      import spray.json._
      import spray.httpx.SprayJsonSupport._
      import DefaultJsonProtocol._
      implicit val client = mockDockerClient
      val dc = client.containers.create("foobar", image).flatMap(_.start).await
      dc.isRunning must beTrue;
      Post("/containers/foobar/stop") ~> route ~> check {
        contentType must_== ContentTypes.`application/json`
        responseAs[JsObject].fields("active") must_== JsBoolean(false)
      }
      dc.refresh.await.isRunning must_== false
    }

    "return a MethodNotAllowed error for DELETE requests to /containers" in {
      implicit val client = mockDockerClient
      Put("/containers") ~> sealRoute(route) ~> check {
        status === MethodNotAllowed
        responseAs[String] === "HTTP method not allowed, supported methods: GET"
      }
    }
  }
}
