import controllers.{Assets, MainController}
import play.api.ApplicationLoader.Context
import play.api._
import play.api.i18n._
import play.api.routing.Router
import router.Routes
import com.softwaremill.macwire._
import com.softwaremill.tagging._
import akka.actor.ActorSystem
import akka.actor.Props
import com.mohiva.play.silhouette.api.{Environment => SilhouetteEnvironment}
import com.mohiva.play.silhouette.api.Silhouette
import com.mohiva.play.silhouette.api.SilhouetteProvider
import com.mohiva.play.silhouette.impl.authenticators.{SessionAuthenticator, SessionAuthenticatorSettings, SessionAuthenticatorService}
import com.mohiva.play.silhouette.api.services._
import utils.auth.DefaultEnv
import com.mohiva.play.silhouette.api.util.Clock
import com.mohiva.play.silhouette.api.EventBus
import com.mohiva.play.silhouette.api.RequestProvider
import com.mohiva.play.silhouette.impl.util.DefaultFingerprintGenerator
import com.mohiva.play.silhouette.impl.util.SecureRandomIDGenerator
import com.mohiva.play.silhouette.api.crypto.Base64AuthenticatorEncoder
import com.mohiva.play.silhouette.api.actions._
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import com.mohiva.play.silhouette.impl.providers.OAuth1Settings
import com.mohiva.play.silhouette.impl.providers.oauth1.TwitterProvider
import com.mohiva.play.silhouette.api.util.PlayHTTPLayer
import play.api.libs.ws.ahc.AhcWSComponents
import com.mohiva.play.silhouette.impl.providers.oauth2.GitHubProvider
import com.mohiva.play.silhouette.impl.providers.OAuth2Settings
import com.mohiva.play.silhouette.impl.providers.oauth2.state.CookieStateProvider
import com.mohiva.play.silhouette.impl.providers.OAuth2StateProvider
import com.mohiva.play.silhouette.impl.providers.oauth2.state.CookieStateSettings
import com.mohiva.play.silhouette.api.crypto.CookieSigner
import com.mohiva.play.silhouette.api.util.HTTPLayer
import com.mohiva.play.silhouette.impl.providers.SocialProvider
import pdi.jwt.JwtJson
import pdi.jwt.JwtAlgorithm
import com.mohiva.play.silhouette.crypto.JcaCookieSigner
import com.mohiva.play.silhouette.crypto.JcaCookieSignerSettings
import com.mohiva.play.silhouette.impl.providers.SocialProviderRegistry
import scala.util.Try

class AppApplicationLoader extends ApplicationLoader {
  def load(context: Context) = {
    LoggerConfigurator(context.environment.classLoader).foreach {
      _.configure(context.environment)
    }
    (new AppComponents(context)).application
  }
}

class AppComponents(context: Context)
    extends BuiltInComponentsFromContext(context) with AhcWSComponents {
  implicit lazy val executionContext = materializer.executionContext
  lazy val router: Router = {
    lazy val prefix = "/"
    wire[Routes]
  }
  lazy val langs: Langs = wire[DefaultLangs]
  lazy val messsages: MessagesApi = wire[DefaultMessagesApi]
  val clusterAggregateManager = actorSystem.actorOf(
      Props(classOf[services.ClusterAggregateManager]))
      .taggedWith[services.ClusterAggregateManager]
  val instanceAggregateManager = actorSystem.actorOf(
      Props(classOf[services.InstanceAggregateManager], clusterAggregateManager))
      .taggedWith[services.InstanceAggregateManager]
  val userAggregateManager = actorSystem.actorOf(
      Props(classOf[services.UserAggregateManager], instanceAggregateManager))
      .taggedWith[services.UserAggregateManager]
  val identityAggregateManager = actorSystem.actorOf(
      Props(classOf[services.IdentityAggregateManager], userAggregateManager))
      .taggedWith[services.IdentityAggregateManager]
  lazy val identityService: services.IdentityService = wire[services.IdentityService]
  lazy val sessionAuthenticatorSettings = SessionAuthenticatorSettings()
  lazy val clock = Clock()
  lazy val eventBus = EventBus()
  lazy val fingerprintGenerator = new DefaultFingerprintGenerator()
  lazy val authenticatorEncoder = new Base64AuthenticatorEncoder()
  lazy val authenticatorService: AuthenticatorService[SessionAuthenticator] = wire[SessionAuthenticatorService]
  lazy val httpLayer: HTTPLayer = wire[PlayHTTPLayer]
  lazy val idGenerator = new SecureRandomIDGenerator()
  lazy val cookieStateSettings = new CookieStateSettings(secureCookie=false)
  lazy val jcaCookieSignerSettings =
    new JcaCookieSignerSettings(configuration.underlying.as[String]("play.crypto.secret"))
  lazy val silhouetteCookieSigner: CookieSigner = wire[JcaCookieSigner]
  lazy val stateProvider: OAuth2StateProvider = wire[CookieStateProvider]
  lazy val socialProviders: Seq[SocialProvider] =
    Seq[Try[SocialProvider]](
      Try {
        val settings = configuration.underlying.as[OAuth2Settings]("silhouette.github")
        new GitHubProvider(httpLayer, stateProvider, settings)
      }
    ).map(_.toOption).flatten
  lazy val socialProviderRegistry = wire[SocialProviderRegistry]
  lazy val silhouetteEnv: SilhouetteEnvironment[DefaultEnv] =
    SilhouetteEnvironment[DefaultEnv](identityService, authenticatorService, Seq.empty, eventBus)
  lazy val securedErrorHandler = wire[DefaultSecuredErrorHandler]
  lazy val securedActionModule = wire[DefaultSecuredRequestHandler]
  lazy val securedAction: SecuredAction = wire[DefaultSecuredAction]
  lazy val unsecuredErrorHandler = wire[DefaultUnsecuredErrorHandler]
  lazy val unsecuredActionModule = wire[DefaultUnsecuredRequestHandler]
  lazy val unsecuredAction: UnsecuredAction = wire[DefaultUnsecuredAction]
  lazy val userAwareActionModule = wire[DefaultUserAwareRequestHandler]
  lazy val userAwareAction: UserAwareAction = wire[DefaultUserAwareAction]
  lazy val silhouette: Silhouette[DefaultEnv] = wire[SilhouetteProvider[DefaultEnv]]
  lazy val mainController = wire[MainController]
  lazy val assetsController = wire[Assets]
}