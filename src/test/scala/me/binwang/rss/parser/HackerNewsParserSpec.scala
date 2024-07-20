package me.binwang.rss.parser

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import me.binwang.rss.fetch.crawler.HttpCrawler
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime
import scala.concurrent.ExecutionContext

class HackerNewsParserSpec extends AnyFunSpec with BeforeAndAfterEach with Matchers {

  implicit val ioRuntime: IORuntime = IORuntime.global
  implicit val executionContext: ExecutionContext = ExecutionContext.global

  describe("Hacker News parser") {

    it("should parse Hacker News API response") {
      val fetcher = HttpCrawler().allocated.unsafeRunSync()._1
      val url = "https://hn.algolia.com/api/v1/search?tags=front_page"

      val resp = fetcher.fetch(url).use(IO.pure).unsafeRunSync()
      val (source, articles) = HackerNewsParser.parse(url, resp, ZonedDateTime.now())
      source.xmlUrl shouldBe url
      articles.length should be > 10
      articles.map(_.isSuccess shouldBe true)
      articles.map(_.get.article.score should be > 0.0)
    }

  }

}
