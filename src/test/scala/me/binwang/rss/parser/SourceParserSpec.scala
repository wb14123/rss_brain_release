package me.binwang.rss.parser

import cats.effect.unsafe.IORuntime
import cats.effect.{IO, Resource}
import me.binwang.rss.fetch.crawler.HttpCrawler
import me.binwang.rss.model.{FullArticle, MediaMedium, Source}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import scala.concurrent.ExecutionContext
import scala.util.Try

class SourceParserSpec extends AnyFunSpec with BeforeAndAfterEach with Matchers {

  implicit val ioRuntime: IORuntime = IORuntime.global

  val testFeedUrl = "https://www.cbc.ca/cmlink/rss-topstories"

  private def inputStream(testFile: String) = Resource.make {
    IO(getClass.getResourceAsStream(testFile))
  } { i =>
    IO(i.close())
  }

  describe("Source parser") {

    it(s"test repeat in half") {
      isRepeatedTwice("abcabc") shouldBe true
      isRepeatedTwice("abcabd") shouldBe false
    }

    it(s"should remove div without attributes which has only 1 child") {
      ArticleParserHelper.prettyHtml("<div><div>abc</div><div></div></div>") shouldBe "<div>\n  abc\n</div>"
    }

    it(s"should parse default icon") {
      ArticleParserHelper.getIcon("https://www.google.com/") shouldBe Some("https://www.google.com/favicon.ico")
      ArticleParserHelper.getIcon("https://www.google.com/search?abc") shouldBe Some("https://www.google.com/favicon.ico")
      ArticleParserHelper.getIcon("http://www.google.com/search?abc") shouldBe Some("http://www.google.com/favicon.ico")
      ArticleParserHelper.getIcon("abc") shouldBe None
      ArticleParserHelper.getIcon("") shouldBe None
    }

    it(s"should parse rss feed") {
      val testFeedFile = "/test-feed.xml"
      val now = ZonedDateTime.now().withNano(0)
      val (source, articles) = SourceParser.parse(testFeedUrl, inputStream(testFeedFile)).unsafeRunSync()
      source.title.get shouldBe "CBC | Top Stories News"
      source.description.get shouldBe "FOR PERSONAL USE ONLY"
      source.htmlUrl.get shouldBe "http://www.cbc.ca/news/?cmp=rss"
      source.xmlUrl shouldBe testFeedUrl
      source.importedAt.isAfter(now) shouldBe true
      articles.size shouldBe 20
      val article = articles.map(_.get).head.article
      article.title shouldBe "Health Canada pauses regulatory approval for COVID-19 rapid test: Latest on coronavirus"
      article.guid shouldBe "1.5433912"
      article.postedAt.toInstant shouldBe ZonedDateTime.parse("2020-01-20T17:11:33.000-05:00", DateTimeFormatter.ISO_ZONED_DATE_TIME).toInstant
      article.link shouldBe "https://www.cbc.ca/news/canada/coronavirus-covid19-1.5553826?cmp=rss"

      val content = articles.map(_.get).head.content
      content shouldBe
        s"""<img src="https://i.cbc.ca/1.5515229.1585599213!/fileImage/httpImage/image.jpg_gen/derivatives/16x9_460/covid-19-testing-device.jpg" alt="COVID-19 testing device" width="460" title="Spartan Bioscience of Ottawa is in the late stages of developing a rapid-testing device for COVID-19." height="259">
          |<p></p>""".stripMargin
    }


    it(s"should parse images from article") {
      val testFeedFile = "/test-feed.xml"
      val now = ZonedDateTime.now().withNano(0)
      val (source, articles) = SourceParser.parse(testFeedUrl, inputStream(testFeedFile)).unsafeRunSync()
      val article = articles.map(_.get).head.article
      val media = article.mediaGroups.get.groups.head
      media.content.fromArticle shouldBe Some(true)
      media.content.medium shouldBe Some(MediaMedium.IMAGE)
      media.content.url shouldBe "https://i.cbc.ca/1.5515229.1585599213!/fileImage/httpImage/image.jpg_gen/derivatives/16x9_460/covid-19-testing-device.jpg"
      media.content.height shouldBe Some(259)
      media.title shouldBe Some("Spartan Bioscience of Ottawa is in the late stages of developing a rapid-testing device for COVID-19.")

      val content = articles.map(_.get).head.content
      content shouldBe
        s"""<img src="https://i.cbc.ca/1.5515229.1585599213!/fileImage/httpImage/image.jpg_gen/derivatives/16x9_460/covid-19-testing-device.jpg" alt="COVID-19 testing device" width="460" title="Spartan Bioscience of Ottawa is in the late stages of developing a rapid-testing device for COVID-19." height="259">
           |<p></p>""".stripMargin
    }

    it(s"should handle invalid article with empty guid and link for rss feed") {
      val testFeedFile = "/test-feed-invalid-article.xml"
      val now = ZonedDateTime.now().withNano(0)
      val (source, articlesTries) = SourceParser.parse(testFeedUrl, inputStream(testFeedFile)).unsafeRunSync()
      val articles = articlesTries.filter(_.isSuccess).map(_.get)
      source.title.get shouldBe "CBC | Top Stories News"
      source.description.get shouldBe "FOR PERSONAL USE ONLY"
      source.htmlUrl.get shouldBe "http://www.cbc.ca/news/?cmp=rss"
      source.xmlUrl shouldBe testFeedUrl
      source.importedAt.isAfter(now) shouldBe true
      articles.size shouldBe 19
      source.fetchFailedMsg.get shouldBe "1 article(s) failed to parse"
      val article = articles.head.article
      article.guid shouldBe "https://www.cbc.ca/news/health/covid-immunity-unknown-1.5553229?cmp=rss"
    }

    it(s"should parse atom feed") {
      val testFeedFile = "/test-feed-atom.xml"
      val now = ZonedDateTime.now().withNano(0)
      val (source, articles) = SourceParser.parse(testFeedUrl, inputStream(testFeedFile)).unsafeRunSync()
      source.title.get shouldBe "Bin Wang - My Personal Blog"
      source.description.get shouldBe "This is my personal blog about computer science, technology and my life."
      source.htmlUrl.get shouldBe "https://www.binwang.me/"
      source.xmlUrl shouldBe testFeedUrl
      source.importedAt.isAfter(now) shouldBe true
      articles.size shouldBe 20
      val article = articles.map(_.get).head.article
      article.title shouldBe "Add Index to My Blog"
      article.guid shouldBe "https://www.binwang.me/Add-Index-to-My-Blog"
      article.postedAt.isEqual(ZonedDateTime.parse(
        "2021-10-31T00:00:00-04:00", DateTimeFormatter.ISO_ZONED_DATE_TIME)) shouldBe true// Mon, 20 Jan 2020 17:11:33 EST
      article.link shouldBe "https://www.binwang.me/2021-10-31-Add-Index-to-My-Blog.html"

      articles.map(_.get).head.content.nonEmpty shouldBe true
    }

    it(s"should parse comments from atom feed") {
      val testFeedFile = "/test-feed-atom-comments.xml"
      val now = ZonedDateTime.now().withNano(0)
      val (_, articles) = SourceParser.parse(testFeedUrl, inputStream(testFeedFile)).unsafeRunSync()
      articles.exists(_.get.article.comments.exists(_ > 0)) shouldBe true
      articles.exists(_.get.article.score > 0) shouldBe true
    }

    it(s"should parse RDF feed") {
      val testFeedFile = "/test-rdf-slashdot.xml"
      val now = ZonedDateTime.now().withNano(0)
      val (source, articles) = SourceParser.parse(testFeedUrl, inputStream(testFeedFile)).unsafeRunSync()
      source.title.get shouldBe "Slashdot"
      source.description.get shouldBe "News for nerds, stuff that matters"
      source.htmlUrl.get shouldBe "https://slashdot.org/"
      source.xmlUrl shouldBe testFeedUrl
      source.importedAt.isAfter(now) shouldBe true
      articles.size shouldBe 15
      articles.count(_.isSuccess) shouldBe 15
    }

    it(s"should parse feed with content:encoded") {
      val testFeedFile = "/test-feed-ign.xml"
      val now = ZonedDateTime.now().withNano(0)
      val (source, articles) = SourceParser.parse(testFeedUrl, inputStream(testFeedFile)).unsafeRunSync()
      source.xmlUrl shouldBe testFeedUrl
      source.importedAt.isAfter(now) shouldBe true
      articles.size should be > 0
      articles.foreach(_.isSuccess shouldBe true)
      articles.foreach(_.get.content.length should be > 300)
    }

    it(s"should parse media tags in atom feed") {
      val testFeedFile = "/test-atom-youtube.xml"
      val (_, articles) = SourceParser.parse(testFeedUrl, inputStream(testFeedFile)).unsafeRunSync()
      checkMediaGroups(articles)
    }

    it(s"should parse media tags in rss feed") {
      val testFeedFile = "/test-feed-peertube.xml"
      val (_, articles) = SourceParser.parse(testFeedUrl, inputStream(testFeedFile)).unsafeRunSync()
      checkMediaGroups(articles)
    }

    it(s"should parse Reddit API feed") {
      val (source, articles) = getArticlesFromSubreddit("news")
      source.title shouldBe Some("/r/news")
      articles.isEmpty shouldBe false
      articles.map(_.isSuccess shouldBe true)
    }

    it(s"should parse Reddit API with images") {
      Seq("unixporn", "China_irl").foreach { subreddit =>
        val (_, articles) = getArticlesFromSubreddit(subreddit)
        articles.exists(a => a.isSuccess && a.get.article.mediaGroups.exists(_.groups.nonEmpty)) shouldBe true
      }
    }

    it(s"should parse Reddit nsfw state") {
      val (_, articles) = getArticlesFromSubreddit("nsfw")
      articles.exists(a => a.isSuccess && a.get.article.nsfw) shouldBe true
    }

    it(s"should parse articles without posted at time") {
      val testFeedFile = "/test-feed-no-posted-at.xml"
      val (_, articles) = SourceParser.parse(testFeedUrl, inputStream(testFeedFile)).unsafeRunSync()
      articles.isEmpty shouldBe false
      articles.foreach {
        _.get.article.postedAtIsMissing shouldBe true
      }
    }

    it("should parse podcast into media groups") {
      val testFeedFile = "/podcast.xml"
      val (_, articles) = SourceParser.parse(testFeedUrl, inputStream(testFeedFile)).unsafeRunSync()
      articles.isEmpty shouldBe false
      articles.foreach { fullArticle =>
        val article = fullArticle.get.article
        article.mediaGroups.isDefined shouldBe true
        article.mediaGroups.get.groups.size shouldBe 1
        val group = article.mediaGroups.get.groups.head
        group.content.medium.get shouldBe MediaMedium.AUDIO
        group.content.url.nonEmpty shouldBe true
        group.title.get.nonEmpty shouldBe true
      }

      articles.exists(_.get.article.mediaGroups.get.groups.head.thumbnails.size == 1) shouldBe true
    }
  }

  private def getArticlesFromSubreddit(subreddit: String): (Source, Seq[Try[FullArticle]]) = {
    val url = s"https://www.reddit.com/r/$subreddit/hot.json?count=100"
    val fetcher = HttpCrawler().allocated.unsafeRunSync()._1
    val resp = fetcher.fetch(url).use(IO.pure).unsafeRunSync()
    val inputStreamResource = Resource.make{IO.pure(resp)}{i => IO(i.close())}
    SourceParser.parse(url, inputStreamResource).unsafeRunSync()
  }

  private def checkMediaGroups(articles: Seq[Try[FullArticle]]) = {
    articles.nonEmpty shouldBe true
    articles.foreach { article =>
      article.isSuccess shouldBe true
      article.get.content.nonEmpty shouldBe true
    }
    articles.exists(_.get.article.description.nonEmpty) shouldBe true
    articles.exists(_.get.article.mediaGroups.exists(_.groups.nonEmpty)) shouldBe true
    articles.exists(_.get.article.mediaGroups.exists(_.groups.exists(_.thumbnails.length == 1))) shouldBe true
    articles.exists(_.get.article.mediaGroups.exists(_.groups.exists(_.title.nonEmpty))) shouldBe true
    articles.exists(_.get.article.mediaGroups.exists(_.groups.exists(_.description.nonEmpty))) shouldBe true
    articles.exists(_.get.article.mediaGroups.exists(_.groups.exists(_.content.medium.contains(MediaMedium.VIDEO)))) shouldBe true
    articles.exists(_.get.article.mediaGroups.exists(_.groups.exists(g => !isRepeatedTwice(g.title.getOrElse(""))))) shouldBe true
  }

  // check whether a string is repeat from the half of it
  private def isRepeatedTwice(str: String): Boolean = {
    if (str.length % 2 != 0) {
      false
    } else {
      val half = str.length / 2
      str.substring(0, half).equals(str.substring(half, str.length))
    }
  }

}
