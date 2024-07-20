package me.binwang.rss.sourcefinder

import cats.effect.unsafe.IORuntime
import cats.effect.IO
import me.binwang.rss.fetch.crawler.{Crawler, HttpCrawler}
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer.startClientAndServer
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class HtmlSourceFinderSpec extends AnyFunSpec with BeforeAndAfterEach with Matchers {

  implicit val ioRuntime: IORuntime = IORuntime.global
  private var mockServer: ClientAndServer = _
  implicit val crawler: Crawler = HttpCrawler().allocated.unsafeRunSync()._1

  private def startMockServer(path: String, content: String): Unit = {
    mockServer = startClientAndServer(9998)
    mockServer.when(request().withPath(path)).respond(
      response()
        .withStatusCode(200)
        .withHeader("Content-Type", "text/html; charset=utf-8")
        .withBody(content)
    )
  }

  override def afterEach(): Unit = {
    if (mockServer != null) mockServer.close()
  }

  describe("html source finder") {

    it("should extract simple rss link") {
      val feedLink = "http://example/feed.xml"
      startMockServer("/test-html", s"""<html><body><link href="$feedLink"></body></html>""")
      val sourceFinder = new HtmlSourceFinder()
      sourceFinder.findSource("http://localhost:9998/test-html").unsafeRunSync()
        .head.url shouldBe feedLink

      mockServer.stop()
      startMockServer("/test-html", s"""<html><body><link href="http://example/a.xml"></body></html>""")
      sourceFinder.findSource("http://localhost:9998/test-html").unsafeRunSync().size shouldBe 0
    }

    it("should extract feed from Youtube channel html") {
      startMockServer("/youtube-channel", scala.io.Source.fromResource("youtube-channel-html-example.html").mkString)
      val sourceFinder = new HtmlSourceFinder()
      sourceFinder.findSource("http://localhost:9998/youtube-channel").unsafeRunSync()
        .head.url shouldBe "https://www.youtube.com/feeds/videos.xml?channel_id=UCP0w_Lr_G7WqnzHwFNApbrw"
    }

  }

}
