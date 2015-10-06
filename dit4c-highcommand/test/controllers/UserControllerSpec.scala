package controllers

import java.util.UUID
import scala.concurrent.ExecutionContext
import org.junit.runner.RunWith
import play.api.libs.json._
import play.api.test.FakeRequest
import play.api.test.PlaySpecification
import providers.db.CouchDB
import providers.db.EphemeralCouchDBInstance
import org.specs2.runner.JUnitRunner
import models._
import providers.auth.Identity
import play.api.test.WithApplication
import play.api.Play
import providers.InjectorPlugin
import utils.SpecUtils

@RunWith(classOf[JUnitRunner])
class UserControllerSpec extends PlaySpecification with SpecUtils {

  "UserController" should {

    "provide JSON for users" in new WithApplication(fakeApp) {
      val controller = injector(app).instanceOf(classOf[UserController])

      val withoutLogin = controller.currentUser(FakeRequest())
      status(withoutLogin) must_== 404

      val session = new UserSession(db(app), new Identity() {
        def uniqueId = "test:testuser"
        def name = Some("Test User")
        def emailAddress = Some("user@example.test")
      })
      val response = controller.get(session.user.id)(session.newRequest)
      status(response) must_== 200
      contentAsJson(response) must_== Json.obj(
        "id" -> session.user.id,
        "name" -> session.user.name.get,
        "email" -> session.user.email.get,
        "identities" -> Json.arr(Json.obj(
          "type" -> "test",
          "id" -> "testuser"
        ))
      )
      val etag: String = header("ETag", response) match {
        case Some(s) => s
        case None => failure("ETag must be sent with user record"); ""
      }
      val ifMatchResponse = controller.get(session.user.id)(
          session.newRequest.withHeaders("If-None-Match" -> etag))
      status(ifMatchResponse) must_== 304
    }

    "provide current user" in new WithApplication(fakeApp) {
      val controller = injector(app).instanceOf(classOf[UserController])

      val withoutLogin = controller.currentUser(FakeRequest())
      status(withoutLogin) must_== 404

      val user = {
        val dao = new UserDAO(injector(app).instanceOf(classOf[CouchDB.Database]))
        await(dao.createWith(new Identity() {
          def uniqueId = "test:testuser"
          def name = Some("Test User")
          def emailAddress = Some("user@example.test")
        }))
      }
      val withLogin = controller.currentUser(
          FakeRequest().withSession("userId" -> user.id))
      status(withLogin) must_== 200
      contentAsJson(withLogin) must_== Json.obj(
        "id" -> user.id,
        "name" -> user.name.get,
        "email" -> user.email.get,
        "identities" -> Json.arr(Json.obj(
          "type" -> "test",
          "id" -> "testuser"
        ))
      )
      val etag: String = header("ETag", withLogin) match {
        case Some(s) => s
        case None => failure("ETag must be sent with user record"); ""
      }
      header("Cache-Control", withLogin) must beSome("private, must-revalidate")
    }

  }

}
