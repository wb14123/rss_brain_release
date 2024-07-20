package me.binwang.rss.cmd

import cats.effect.{IO, Resource}
import com.comcast.ip4s.{Ipv4Address, Port}
import org.http4s.HttpRoutes
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import org.http4s.server.Server
import org.http4s.server.middleware.{ErrorAction, Logger}
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

    val httpService = ErrorAction.httpRoutes[IO](
      loggingService,
      (req, thr) => http4sLogger.error(thr)(s"Error when handling request: ${req.uri}")
    ).orNotFound

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
