package providers.db

import com.google.inject.Provider
import play.api.{Application,Plugin}
import java.io.File
import java.util.UUID
import akka.actor.ActorSystem
import akka.util.Timeout
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import java.util.concurrent.TimeUnit

class CouchDBPlugin(implicit app: Application) extends Plugin with Provider[CouchDB.Instance] {

  implicit def ec: ExecutionContext = play.api.libs.concurrent.Execution.defaultContext
  implicit def system: ActorSystem = play.api.libs.concurrent.Akka.system(app)

  implicit val timeout: Timeout = Timeout(5, TimeUnit.SECONDS)

  lazy val log = play.api.Logger

  lazy val serverInstance: ManagedCouchDBInstance =
    if (app.configuration.getBoolean("couchdb.testing").getOrElse(false)) {
      new EphemeralCouchDBInstance
    } else {
      new PersistentCouchDBInstance("./db", 40000)
    }

  def get = serverInstance

  override def onStart {
    serverInstance // Ensure DB is initialized before app start
  }

  override def onStop {
    serverInstance.shutdown
  }

}