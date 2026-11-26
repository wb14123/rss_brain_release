package me.binwang.rss.fetch.crawler

import cats.effect._
import cats.effect.unsafe.IORuntime
import me.binwang.rss.model.SsrfBlockedError
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer.startClientAndServer
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class HttpCrawlerSpec extends AnyFunSpec with BeforeAndAfterEach with Matchers {

  implicit val ioRuntime: IORuntime = IORuntime.global

  var mockServer: ClientAndServer = _

  override def beforeEach(): Unit = {
    mockServer = startClientAndServer(9998)
    mockServer.when(request().withPath("/valid")).respond(response("hello"))
    mockServer.when(request().withPath("/404")).respond(
      response("404 not found").withStatusCode(404))
    mockServer.when(request().withPath("/redirect-to-private")).respond(
      response().withStatusCode(302).withHeader("Location", "http://169.254.169.254/latest/meta-data/"))
    mockServer.when(request().withPath("/redirect-to-valid")).respond(
      response().withStatusCode(302).withHeader("Location", "http://localhost:9998/valid"))
    mockServer.when(request().withPath("/redirect-to-blocked-port")).respond(
      response().withStatusCode(302).withHeader("Location", "http://localhost:9999/valid"))
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

  describe("SSRF protection") {

    it("should block redirect to private IP (169.254.169.254)") {
      val validator = new UrlValidator(Seq("localhost:9998"))
      sttp.client4.http4s.Http4sBackend.usingDefaultEmberClientBuilder[IO]()
        .use { backend =>
          val crawler = new HttpCrawler(backend, validator, 10)
          val result = crawler.fetch("http://localhost:9998/redirect-to-private").use { i =>
            IO(i.readAllBytes())
          }.attempt
          result.map {
            case Left(e: SsrfBlockedError) =>
              e.url should include("169.254.169.254")
            case Left(other) => fail(s"Expected SsrfBlockedError but got: $other")
            case Right(_) => fail("Expected SsrfBlockedError but request succeeded")
          }
        }.unsafeRunSync()
    }

    it("should allow redirect to an allowlisted host and port") {
      val validator = new UrlValidator(Seq("localhost:9998"))
      sttp.client4.http4s.Http4sBackend.usingDefaultEmberClientBuilder[IO]()
        .use { backend =>
          val crawler = new HttpCrawler(backend, validator, 10)
          crawler.fetch("http://localhost:9998/redirect-to-valid").use { i =>
            IO(new String(i.readAllBytes()) shouldBe "hello")
          }
        }.unsafeRunSync()
    }

    it("should block redirect to a port that is not allowlisted for the host") {
      val validator = new UrlValidator(Seq("localhost:9998"))
      sttp.client4.http4s.Http4sBackend.usingDefaultEmberClientBuilder[IO]()
        .use { backend =>
          val crawler = new HttpCrawler(backend, validator, 10)
          val result = crawler.fetch("http://localhost:9998/redirect-to-blocked-port").use { i =>
            IO(i.readAllBytes())
          }.attempt
          result.map {
            case Left(e: SsrfBlockedError) =>
              e.reason should include("port 9999")
            case Left(other) => fail(s"Expected SsrfBlockedError but got: $other")
            case Right(_) => fail("Expected SsrfBlockedError but request succeeded")
          }
        }.unsafeRunSync()
    }
  }
}
