package me.binwang.rss.webview.basic

import cats.data.{Kleisli, OptionT}
import cats.effect.IO
import cats.syntax.all._
import me.binwang.rss.model.{ServerException, UserNotAuthorized, UserSubscriptionEnded}
import org.http4s.dsl.io._
import org.http4s.{HttpRoutes, Request}
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

object ErrorHandler {

  implicit val loggerFactory: LoggerFactory[IO] = Slf4jFactory.create[IO]
  private val logger = LoggerFactory.getLoggerFromClass[IO](this.getClass)

  case class RedirectDefaultFolderException(exception: Throwable) extends Exception

  def apply(service: HttpRoutes[IO]): HttpRoutes[IO] = Kleisli { (req: Request[IO]) => {
    service(req).handleErrorWith {
      case UserNotAuthorized(_) =>
        OptionT.liftF(HttpResponse.redirect("user not authorized", "/login", req))
      case UserSubscriptionEnded(_, _) =>
        OptionT.liftF(HttpResponse.redirect("subscription ended", "/payment/renew", req))
      case RedirectDefaultFolderException(_) =>
        OptionT.liftF(HttpResponse.fullRedirect("redirect to default folder", "/folders/default"))
      case x: ServerException =>
        OptionT.liftF(logger.error(x)("Server error while handle HTTP request") >> InternalServerError(x.getMessage))
      case x =>
        OptionT.liftF(logger.error(x)("Unknown error while handle http request") >> InternalServerError(x.getMessage))
    }
  }}

}
