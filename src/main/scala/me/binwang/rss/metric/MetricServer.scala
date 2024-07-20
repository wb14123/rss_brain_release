package me.binwang.rss.metric

import cats.effect.{IO, Resource}
import com.comcast.ip4s.{IpLiteralSyntax, Port}
import com.typesafe.config.ConfigFactory
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.hotspot.DefaultExports
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.metrics.prometheus.PrometheusExportService
import org.http4s.server.{Router, Server}

object MetricServer {

  def apply(): Resource[IO, Server] = {
    val config = ConfigFactory.load()
    val port = config.getInt("metrics.server-port")

    val httpService = Router("/" -> PrometheusExportService[IO](CollectorRegistry.defaultRegistry).routes).orNotFound
    for {
      _ <- Resource.eval(IO.blocking(DefaultExports.initialize()))
      port <- Resource.pure(Port.fromInt(port).getOrElse(throw new Exception("Invalid http.port configured")))
      server <- EmberServerBuilder
        .default[IO]
        .withHost(ipv4"0.0.0.0")
        .withPort(port)
        .withHttpApp(httpService)
        .build
    } yield server
  }

}
