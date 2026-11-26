package me.binwang.rss.cmd

import cats.data.Kleisli
import cats.effect.{IO, Resource}
import com.comcast.ip4s.{Ipv4Address, Port}
import org.http4s.{HttpApp, HttpRoutes, Request}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import org.http4s.server.Server
import org.http4s.server.middleware.{ErrorAction, GZip, Logger}
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

object HttpServer {

  implicit val loggerFactory: LoggerFactory[IO] = Slf4jFactory.create[IO]

  def apply(routes: HttpRoutes[IO], ip: String, port: Int, logBody: Boolean): Resource[IO, Server] = {

    val http4sLogger = LoggerFactory.getLoggerFromName("http4s-logger")
    val loggingService = Logger.httpRoutes[IO](
      logHeaders = false,
      logBody = logBody,
      redactHeadersWhen = _ => false,
      logAction = Some((msg: String) => http4sLogger.info(msg)),
    )(routes)

    val httpApp = ErrorAction.httpRoutes[IO](
      GZip(loggingService),
      (req, thr) => http4sLogger.error(thr)(s"Error when handling request: ${req.uri}")
    ).orNotFound

    /*
    Response body errors happen after the status and headers are sent, so they can't change the response any more.
    They are logged here because the error handlers only see the errors raised before the response is created.
     */
    val httpService: HttpApp[IO] = Kleisli { (req: Request[IO]) =>
      httpApp(req).map { response =>
        response.withBodyStream(response.body.onFinalizeCase {
          case Resource.ExitCase.Succeeded => IO.unit
          case Resource.ExitCase.Errored(err) =>
            http4sLogger.error(err)(s"Error when writing response body: ${req.method} ${req.uri}")
          case Resource.ExitCase.Canceled =>
            http4sLogger.warn(s"Response body writing cancelled: ${req.method} ${req.uri}")
        })
      }
    }

    for {
      ip <- Resource.pure(Ipv4Address.fromString(ip)
        .getOrElse(throw new Exception("Wrong http.ip configured")))
      port <- Resource.pure(Port.fromInt(port)
        .getOrElse(throw new Exception("Invalid http.port configured")))
      server <- EmberServerBuilder
        .default[IO]
        .withHost(ip)
        .withPort(port)
        .withHttpApp(httpService)
        .build
    } yield server

  }

}
