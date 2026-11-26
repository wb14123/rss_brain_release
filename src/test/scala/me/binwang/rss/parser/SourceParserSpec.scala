package me.binwang.rss.parser

import cats.effect.unsafe.IORuntime
import cats.effect.{IO, Resource}
import me.binwang.rss.model.{ContentCompleteness, FullArticle, MediaMedium}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
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
      ArticleParserHelper.prettyHtml("<div><div>abc</div><div></div></div>") shouldBe "<div>abc</div>"
    }

    it(s"should parse default icon") {
      ArticleParserHelper.getIcon("https://www.google.com/") shouldBe Some("https://www.google.com/favicon.ico")
      ArticleParserHelper.getIcon("https://www.google.com/search?abc") shouldBe Some("https://www.google.com/favicon.ico")
      ArticleParserHelper.getIcon("http://www.google.com/search?abc") shouldBe Some("http://www.google.com/favicon.ico")
      ArticleParserHelper.getIcon("abc") shouldBe None
      ArticleParserHelper.getIcon("") shouldBe None
    }

    it(s"should parse rss icon if there is webfeeds:icon") {
      val testFeedFile = "/test-feed-mastodon-jepsen.xml"
      val (source, _) = SourceParser.parse(testFeedUrl, inputStream(testFeedFile)).unsafeRunSync()
      source.iconUrl shouldBe Some("https://cdn.masto.host/mastodonjepsenio/accounts/avatars/000/000/001/original/729178b67437abd9.png")
    }

    it(s"should parse rss icon if there is image url") {
      val testFeedFile = "/test-feed.xml"
      val (source, _) = SourceParser.parse(testFeedUrl, inputStream(testFeedFile)).unsafeRunSync()
      source.iconUrl shouldBe Some("https://www.cbc.ca/rss/image/cbc_144.gif")
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
      article.author shouldBe None

      articles.map(_.get).head.content.nonEmpty shouldBe true
    }

    it(s"should parse comments from atom feed") {
      val testFeedFile = "/test-feed-atom-comments.xml"
      val now = ZonedDateTime.now().withNano(0)
      val (_, articles) = SourceParser.parse(testFeedUrl, inputStream(testFeedFile)).unsafeRunSync()
      articles.exists(_.get.article.comments.exists(_ > 0)) shouldBe true
      articles.exists(_.get.article.score > 0) shouldBe true
    }

    it(s"should parse opengarden comment counts and comment url from atom feed") {
      val testFeedFile = "/test-feed-atom-opengarden.xml"
      val (_, articles) = SourceParser.parse(testFeedUrl, inputStream(testFeedFile)).unsafeRunSync()
      articles.size shouldBe 5

      val withCommentUrl = articles.head.get.article
      withCommentUrl.author shouldBe Some("someone")
      withCommentUrl.upVotes shouldBe Some(36)
      withCommentUrl.downVotes shouldBe Some(4)
      withCommentUrl.comments shouldBe Some(12)
      withCommentUrl.commentUrl shouldBe Some(
        "http://opengarden.example.com:9020/comments?profile=default&fetcher=reddit" +
          "&article_url=https%3A%2F%2Fwww.reddit.com%2Fr%2Ftechnology%2Fcomments%2Fabc123%2Fa_post%2F")

      val withoutCommentUrl = articles(1).get.article
      withoutCommentUrl.author shouldBe Some("someone else")
      withoutCommentUrl.upVotes shouldBe Some(7)
      withoutCommentUrl.downVotes shouldBe None
      withoutCommentUrl.comments shouldBe Some(3)
      withoutCommentUrl.commentUrl shouldBe None
    }

    it(s"should parse opengarden full content url and content completeness from atom feed") {
      val testFeedFile = "/test-feed-atom-opengarden.xml"
      val (_, articles) = SourceParser.parse(testFeedUrl, inputStream(testFeedFile)).unsafeRunSync()

      val full = articles(2).get.article
      full.fullContentUrl shouldBe None
      full.contentCompleteness shouldBe Some(ContentCompleteness.FULL)
      // published wins over updated
      full.postedAt.toInstant shouldBe ZonedDateTime.parse("2024-04-05T08:00:00.000Z").toInstant

      val partial = articles(3).get.article
      partial.fullContentUrl shouldBe Some(
        "http://opengarden.example.com:9020/articles?profile=default&fetcher=webpage" +
          "&article_url=https%3A%2F%2Fwww.binwang.me%2Fpartial-body-article.html")
      partial.contentCompleteness shouldBe Some(ContentCompleteness.PARTIAL)

      val unknown = articles(4).get.article
      unknown.fullContentUrl shouldBe Some(
        "http://opengarden.example.com:9020/articles?profile=default&fetcher=webpage" +
          "&article_url=https%3A%2F%2Fwww.binwang.me%2Funknown-body-article.html")
      unknown.contentCompleteness shouldBe None
    }

    it(s"should parse full content url without the opengarden namespace") {
      val articles = parseXml(atomFeed("<article-url>https://example.com/articles?article_url=abc</article-url>"))
      articles.head.get.article.fullContentUrl shouldBe Some("https://example.com/articles?article_url=abc")
      articles.head.get.article.contentCompleteness shouldBe None
    }

    it(s"should ignore an article url that is empty") {
      val articles = parseXml(atomFeed("<article-url>  </article-url>"))
      articles.head.get.article.fullContentUrl shouldBe None
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

    it("should parse rss that only has thumbnails") {
      val testFeedFile = "/test-feed-bbc-sports-rss.xml"
      val (_, articles) = SourceParser.parse(testFeedUrl, inputStream(testFeedFile)).unsafeRunSync()
      articles.isEmpty shouldBe false
      articles.foreach { a =>
        val mediaGroups = a.get.article.mediaGroups
        mediaGroups.isDefined shouldBe true
        mediaGroups.get.groups.nonEmpty shouldBe true
        mediaGroups.get.groups.head.content.url.isEmpty shouldBe false
        mediaGroups.get.groups.head.content.medium.get shouldBe MediaMedium.IMAGE
      }
    }

    it("should parse author from atom feed") {
      val articles = parseXml(atomFeed("<author><name>Jane Doe</name></author>"))
      articles.size shouldBe 1
      articles.head.get.article.author shouldBe Some("Jane Doe")
    }

    it("should trim the author name with CDATA in atom feed") {
      val articles = parseXml(atomFeed("<author><name><![CDATA[ Jane Doe ]]></name></author>"))
      articles.head.get.article.author shouldBe Some("Jane Doe")
    }

    it("should not set author if atom entry has no author") {
      parseXml(atomFeed("")).head.get.article.author shouldBe None
      parseXml(atomFeed("<author><name/></author>")).head.get.article.author shouldBe None
      parseXml(atomFeed("<author><name>  </name></author>")).head.get.article.author shouldBe None
      // The feed level author and the author of the source element are not the author of the article
      parseXml(atomFeed("", "<author><name>Example Site</name></author>")).head.get.article.author shouldBe None
      parseXml(atomFeed(
        """<source>
          |      <id>https://example.com/</id>
          |      <title>Test feed</title>
          |      <author><name>Example Site</name></author>
          |    </source>""".stripMargin)).head.get.article.author shouldBe None
    }

    it("should parse author from rss feed") {
      rssAuthor("<dc:creator>Matt Kim</dc:creator>") shouldBe Some("Matt Kim")
      rssAuthor("<itunes:author>NPR</itunes:author>") shouldBe Some("NPR")
      rssAuthor("<author>Amina Zafar</author>") shouldBe Some("Amina Zafar")
      // RSS 2.0 puts the author's email address in the author element, and the name can follow in parentheses
      rssAuthor("<author>jane.doe@example.com (Jane Doe)</author>") shouldBe Some("Jane Doe")
    }

    it("should prefer dc:creator and itunes:author over author element in rss feed") {
      rssAuthor(
        """<author>someone@example.com (Someone Else)</author>
          |      <dc:creator>Matt Kim</dc:creator>""".stripMargin) shouldBe Some("Matt Kim")
      rssAuthor(
        """<author>someone@example.com (Someone Else)</author>
          |      <itunes:author>NPR</itunes:author>""".stripMargin) shouldBe Some("NPR")
    }

    it("should not set author if rss item has no author") {
      rssAuthor("") shouldBe None
      rssAuthor("<author/>") shouldBe None
    }

    it("should parse author from dc:creator in peertube feed") {
      val (_, articles) = SourceParser.parse(testFeedUrl, inputStream("/test-feed-peertube.xml")).unsafeRunSync()
      articles.nonEmpty shouldBe true
      articles.foreach { article =>
        article.isSuccess shouldBe true
        article.get.article.author shouldBe Some("veggero")
      }
    }

    it("should parse author from dc:creator in rdf feed") {
      val (_, articles) = SourceParser.parse(testFeedUrl, inputStream("/test-rdf-slashdot.xml")).unsafeRunSync()
      articles.nonEmpty shouldBe true
      articles.exists(_.get.article.author.contains("BeauHD")) shouldBe true
      articles.exists(_.get.article.author.contains("msmash")) shouldBe true
    }

    it("should parse author from author element in cbc feed") {
      val (_, articlesTries) = SourceParser.parse(testFeedUrl, inputStream("/test-feed-invalid-article.xml")).unsafeRunSync()
      val articles = articlesTries.filter(_.isSuccess)
      articles.nonEmpty shouldBe true
      articles.head.get.article.author shouldBe Some("Amina Zafar")
      articles.last.get.article.author shouldBe Some("Michelle Ghoussoub")
    }
  }

  private def parseXml(xml: String): Seq[Try[FullArticle]] = {
    val content = Resource.make {
      IO(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
    } { i =>
      IO(i.close())
    }
    SourceParser.parse(testFeedUrl, content).unsafeRunSync()._2
  }

  private def atomFeed(entryElements: String, feedElements: String = ""): String = {
    s"""<?xml version="1.0" encoding="UTF-8"?>
       |<feed xmlns="http://www.w3.org/2005/Atom">
       |  <id>https://example.com/</id>
       |  <title>Test feed</title>
       |  <updated>2024-01-01T00:00:00Z</updated>
       |  <link href="https://example.com/" rel="alternate" type="text/html"/>
       |  $feedElements
       |  <entry>
       |    <id>https://example.com/article</id>
       |    <title>Test article</title>
       |    <link href="https://example.com/article.html" rel="alternate" type="text/html"/>
       |    <updated>2024-01-01T00:00:00Z</updated>
       |    $entryElements
       |    <content type="html">&lt;p&gt;article body&lt;/p&gt;</content>
       |  </entry>
       |</feed>""".stripMargin
  }

  private def rssAuthor(itemAuthor: String): Option[String] = {
    val xml =
      s"""<?xml version="1.0" encoding="UTF-8"?>
         |<rss version="2.0"
         |  xmlns:dc="http://purl.org/dc/elements/1.1/"
         |  xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
         |  <channel>
         |    <title>Test feed</title>
         |    <link>https://example.com/</link>
         |    <description>Test feed</description>
         |    <item>
         |      <title>Test article</title>
         |      <link>https://example.com/article.html</link>
         |      <guid>https://example.com/article.html</guid>
         |      <pubDate>Mon, 01 Jan 2024 00:00:00 GMT</pubDate>
         |      $itemAuthor
         |    </item>
         |  </channel>
         |</rss>""".stripMargin
    val articles = parseXml(xml)
    articles.size shouldBe 1
    articles.head.get.article.author
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
