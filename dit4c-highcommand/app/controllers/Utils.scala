package controllers

import play.api._
import play.api.mvc._
import scala.io.Source
import com.nimbusds.jose._
import com.nimbusds.jose.jwk._
import com.nimbusds.jose.crypto.RSASSASigner
import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.io.{BufferedWriter, FileWriter, File, FileNotFoundException}
import play.api.libs.json.{Json,Writes}
import scala.collection.JavaConversions._
import java.util.Calendar
import com.nimbusds.jwt.JWTParser
import scala.util.Try
import utils.jwt._
import providers.auth._
import com.google.inject.Inject
import providers.db.CouchDB
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import play.mvc.Http.RequestHeader
import models._
import akka.util.Timeout
import java.util.concurrent.TimeUnit
import providers.ContainerResolver

trait Utils extends Results {
  import scala.language.implicitConversions
  import play.api.http.HeaderNames._

  implicit def ec: ExecutionContext =
    play.api.libs.concurrent.Execution.defaultContext

  protected def db: CouchDB.Database

  protected lazy val accessTokenDao = new AccessTokenDAO(db)
  protected lazy val containerDao = new ContainerDAO(db)
  protected lazy val keyDao = new KeyDAO(db)
  protected lazy val userDao = new UserDAO(db)
  protected lazy val computeNodeDao = new ComputeNodeDAO(db, keyDao)

  implicit class JwtHelper(response: Result)(implicit request: Request[_]) {
    def withUpdatedJwt(user: User, cr: ContainerResolver): Future[Result] =
      for {
        containers <- userContainers(user)
        jwt <- createJWT(containers.map(cr.asName))
      } yield response.withCookies(jwtCookie(jwt))

    def withClearedJwt: Future[Result] =
      Future.successful(response.withCookies(jwtCookie("")))

    protected def jwtCookie(data: String): Cookie =
      Cookie("dit4c-jwt", data, domain=getCookieDomain)
  }

  class AuthenticatedRequest[A](val user: User, request: Request[A])
    extends WrappedRequest[A](request)

  object Authenticated extends ActionBuilder[AuthenticatedRequest] {
    import play.api.http.HeaderNames._
    override def invokeBlock[A](
        request: Request[A],
        block: (AuthenticatedRequest[A]) => Future[Result]
        ): Future[Result] = {
      fetchUser(request).flatMap { possibleUser =>
        possibleUser match {
          case Some(user) =>
            for {
              result <- block(new AuthenticatedRequest(user, request))
              ccDirectives = "private" :: "must-revalidate" ::
                  result.header.headers.get(CACHE_CONTROL).toList
              privateResult = result.withHeaders(
                  CACHE_CONTROL -> ccDirectives.mkString(", "))
            } yield privateResult
          case None => Future.successful(Forbidden)
        }
      }
    }
  }

  def ifNoneMatch[A](etag: String)(r: => Result)(implicit req: Request[A]): Result = {
    req.headers.get(IF_NONE_MATCH) match {
      case Some(v) if etag == v =>
        NotModified
      case _ =>
        r.withHeaders(ETAG -> etag)
    }
  }

  protected def getCookieDomain(implicit request: Request[_]): Option[String] =
    if (request.host.matches(".+\\..+")) Some("."+request.host)
    else None

  protected def userContainers(user: User): Future[Seq[Container]] =
    containerDao.list.map { containers =>
      containers.filter(_.ownerIDs.contains(user.id))
    }

  protected def fetchUser(implicit request: Request[_]): Future[Option[User]] =
    request.session.get("userId")
      .map(userDao.get) // Get user if userId exists
      .getOrElse(Future.successful(None))

  private def createJWT(containers: Seq[String])(implicit request: Request[_]) =
    bestPrivateKey.map { jwk =>
      val privateKey = jwk.toRSAPrivateKey
      val tokenString = {
        val json = Json.obj(
            "iis" -> request.host,
            "iat" -> System.currentTimeMillis / 1000,
            "http://dit4c.github.io/authorized_containers" -> containers
          )
        json.toString
      }
      val header = (new JWSHeader.Builder(JWSAlgorithm.RS256))
        // Keyset URL, which we'll set because we have one
        .jwkURL(new java.net.URI(
          routes.AuthController.publicKeys().absoluteURL(request.secure)))
        .build
      val payload = new Payload(tokenString)
      val signer = new RSASSASigner(privateKey)
      val token = new JWSObject(header, payload)
      token.sign(signer)
      token.serialize
    }

  // While it's possible not to have a valid key, it's a pretty big error
  private def bestPrivateKey: Future[RSAKey] =
    keyDao.bestSigningKey.map {
      case Some(k) => k.toJWK
      case None =>
        throw new RuntimeException("No valid private keys are available!")
    }

  implicit lazy val userWriter = new Writes[User]() {
    override def writes(u: User) = Json.obj(
      "id"    -> u.id,
      "name"  -> u.name,
      "email" -> u.email,
      "identities" -> u.identities.map { s =>
        val Array(t, id) = s.split(":", 2)
        Map("type" -> t, "id" -> id)
      }
    )
  }

}
