package me.binwang.rss.metric

import cats.effect.{IO, Resource}
import com.comcast.ip4s.{IpLiteralSyntax, Port}
import com.typesafe.config.ConfigFactory
import io.prometheus.metrics.expositionformats.PrometheusTextFormatWriter
import io.prometheus.metrics.instrumentation.jvm.JvmMetrics
import io.prometheus.metrics.model.registry.PrometheusRegistry
import org.http4s.{Header, HttpRoutes}
import org.http4s.dsl.io._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.{Router, Server}
import org.typelevel.ci.CIString

import java.io.ByteArrayOutputStream

object MetricServer {

  private val textFormatWriter = PrometheusTextFormatWriter.create()

  private val contentTypeHeader = Header.Raw(CIString("Content-Type"), textFormatWriter.getContentType)

  private[metric] val routes = HttpRoutes.of[IO] {
    case GET -> Root / "metrics" =>
      IO.blocking {
        val out = new ByteArrayOutputStream()
        textFormatWriter.write(out, PrometheusRegistry.defaultRegistry.scrape())
        out.toByteArray
      }.flatMap(bytes => Ok(bytes, contentTypeHeader))
  }

  def apply(): Resource[IO, Server] = {
    val config = ConfigFactory.load()
    val port = config.getInt("metrics.server-port")

    for {
      _ <- Resource.eval(IO.blocking(JvmMetrics.builder().register()))
      port <- Resource.pure(Port.fromInt(port).getOrElse(throw new Exception("Invalid http.port configured")))
      server <- EmberServerBuilder
        .default[IO]
        .withHost(ipv4"0.0.0.0")
        .withPort(port)
        .withHttpApp(Router("/" -> routes).orNotFound)
        .build
    } yield server
  }

}
