package me.binwang.rss.metric

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import org.http4s.{Header, Method, Request, Status, Uri}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.CIString

import java.nio.charset.StandardCharsets

class MetricServerSpec extends AnyFunSpec with Matchers {

  implicit val ioRuntime: IORuntime = IORuntime.global

  private val contentTypeHeader = Header.Raw(CIString("Content-Type"), "text/plain; version=0.0.4; charset=utf-8")

  describe("Metric server") {

    it("should serve collected metrics in prometheus text format") {
      MetricReporter.countArticleNotUpdated().unsafeRunSync()
      val response = MetricServer.routes.orNotFound.run(
        Request[IO](Method.GET, Uri.unsafeFromString("/metrics"))).unsafeRunSync()
      val body = new String(response.body.compile.to(Array).unsafeRunSync(), StandardCharsets.UTF_8)

      response.status shouldBe Status.Ok
      response.headers.headers.filter(_.name == contentTypeHeader.name) shouldBe Seq(contentTypeHeader)
      body should include("article_not_updated_total")
    }

  }

}
