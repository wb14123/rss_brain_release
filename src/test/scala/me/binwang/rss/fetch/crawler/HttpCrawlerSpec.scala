package me.binwang.rss.fetch.crawler

import cats.effect._
import cats.effect.unsafe.IORuntime
import cats.implicits._
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer.startClientAndServer
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.ExecutionContext
import scala.language.postfixOps


class HttpCrawlerSpec extends AnyFunSpec with BeforeAndAfterEach with Matchers {

  implicit val ioRuntime: IORuntime = IORuntime.global

  var mockServer: ClientAndServer = _

  override def beforeEach(): Unit = {
    mockServer = startClientAndServer(9998)
    mockServer.when(request().withPath("/valid")).respond(response("hello"))
    mockServer.when(request().withPath("/404")).respond(
      response("404 not found").withStatusCode(404))
  }

  override def afterEach(): Unit = {
    if (mockServer != null) {
      mockServer.stop()
    }
  }

  private def checkFetch(fetcher: HttpCrawler, url: String) = {
    fetcher.fetch(url).use{i => IO(i.readAllBytes().length > 0 shouldBe true)}
  }

  describe("Fetcher") {

    val testUrl = "http://localhost:9998/valid"

    it(s"should fetch $testUrl") {
      val fetcher = HttpCrawler().allocated.unsafeRunSync()._1
      checkFetch(fetcher, testUrl).unsafeRunSync()
      checkFetch(fetcher, testUrl).unsafeRunSync()
    }

  }
}
