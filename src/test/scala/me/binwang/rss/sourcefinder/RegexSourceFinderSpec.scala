package me.binwang.rss.sourcefinder

import cats.effect.unsafe.IORuntime
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class RegexSourceFinderSpec extends AnyFunSpec with BeforeAndAfterEach with Matchers{

  implicit val ioRuntime: IORuntime = IORuntime.global

  describe("Regex source finder") {

    it("should extract regex group names from rule source") {
      RegexSourceFinder.parseRegexGroupNames(
        "(?<proto>http(s?))://www.youtube.com/channel/(?<channel>[\\w|_]+)") shouldBe Seq("proto", "channel")
    }

    it("should match a simple rule") {
      val source = "(?<proto>http(s?))://www.youtube.com/channel/(?<channel>[\\w|_]+)"
      val rule = RegexMatchRule(
        source = source.r,
        target = "$proto://www.youtube.com/feeds/videos.xml?channel_id=$channel",
        names = RegexSourceFinder.parseRegexGroupNames(source),
      )

      RegexSourceFinder.matchRule("https://www.youtube.com/channel/UCP0w_Lr_G7WqnzHwFNApbrw",
        rule).get.url shouldBe "https://www.youtube.com/feeds/videos.xml?channel_id=UCP0w_Lr_G7WqnzHwFNApbrw"
    }

    it("should load from rules file") {
      val regexSourceFinder = RegexSourceFinder("test-source-finder-regex-rules.json", Some(getClass.getClassLoader)).unsafeRunSync()
      regexSourceFinder.findSource("https://www.youtube.com/channel/UCP0w_Lr_G7WqnzHwFNApbrw")
        .unsafeRunSync().head.url shouldBe "https://www.youtube.com/feeds/videos.xml?channel_id=UCP0w_Lr_G7WqnzHwFNApbrw"

      val redditMatches = regexSourceFinder.findSource("https://www.reddit.com/r/news").unsafeRunSync()
      redditMatches.size shouldBe 2
      redditMatches.head.url shouldBe "https://www.reddit.com/r/news/hot.json?count=100"
      redditMatches(1).url shouldBe "https://www.reddit.com/r/news/new.json?count=100"
    }

    it("should match hacker news with default rules") {
      val regexSourceFinder = RegexSourceFinder("rssbrain-regex-rules.json").unsafeRunSync()
      regexSourceFinder.findSource("https://news.ycombinator.com/").unsafeRunSync()
        .head.url shouldBe "https://hn.algolia.com/api/v1/search?tags=front_page&hitsPerPage=50"
    }


    it("should match reddit with default rules") {
      val regexSourceFinder = RegexSourceFinder("rssbrain-regex-rules.json").unsafeRunSync()
      val redditMatches = regexSourceFinder.findSource("https://www.reddit.com/r/news").unsafeRunSync()
      redditMatches.size shouldBe 2
      redditMatches.head.url shouldBe "https://www.reddit.com/r/news/hot.json?count=100"
      redditMatches(1).url shouldBe "https://www.reddit.com/r/news/new.json?count=100"
    }

    it("should load from rsshub rules file") {
      val regexSourceFinder = RegexSourceFinder("rsshub-regex-rules.json").unsafeRunSync()
      val rssUrl = regexSourceFinder.findSource("https://www.youtube.com/channel/UCP0w_Lr_G7WqnzHwFNApbrw").unsafeRunSync().head.url
      rssUrl shouldBe "https://rsshub.app/youtube/channel/UCP0w_Lr_G7WqnzHwFNApbrw?format=atom&mode=fulltext"
    }

    it("should load from rsshub rules file and match url with suffix") {
      val regexSourceFinder = RegexSourceFinder("rsshub-regex-rules.json").unsafeRunSync()
      val rssUrl = regexSourceFinder.findSource("https://www.douban.com/group/moneydontmatter/?ref=sidebar").unsafeRunSync().head.url
      rssUrl shouldBe "https://rsshub.app/douban/group/moneydontmatter?format=atom&mode=fulltext"
    }

    it("should find lemmy instance without @ in the community name") {
      val regexSourceFinder = RegexSourceFinder("rssbrain-regex-rules.json").unsafeRunSync()
      val rssUrl = regexSourceFinder.findSource("https://lemmy-abc-123.ml/c/linux").unsafeRunSync().head.url
      rssUrl shouldBe "https://rsshub.app/lemmy/linux@lemmy-abc-123.ml/Hot?format=atom"
    }

    it("should find lemmy instance with @ in the community name") {
      val regexSourceFinder = RegexSourceFinder("rssbrain-regex-rules.json").unsafeRunSync()
      val rssUrl = regexSourceFinder.findSource("https://lemmy.world/c/linux@lemmy-abc-123.ml").unsafeRunSync().head.url
      rssUrl shouldBe "https://rsshub.app/lemmy/linux@lemmy-abc-123.ml/Hot?format=atom"
    }

  }

}
