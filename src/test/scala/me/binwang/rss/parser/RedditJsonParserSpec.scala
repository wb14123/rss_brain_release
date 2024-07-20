package me.binwang.rss.parser

import org.scalatest.BeforeAndAfterEach
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class RedditJsonParserSpec extends AnyFunSpec with BeforeAndAfterEach with Matchers {

  describe("RedditJsonParser") {

    it("should parse subreddit name from URL") {
      RedditJsonParser.getSubRedditFromUrl("https://www.reddit.com/r/javascript/hot.json?count=100") shouldBe Some("javascript")
      RedditJsonParser.getSubRedditFromUrl("https://www.reddit.com/r/javascript/hot.json") shouldBe Some("javascript")
      RedditJsonParser.getSubRedditFromUrl("https://www.reddit.com/r/Abc123/hot.json") shouldBe Some("Abc123")
    }

    it("should parse post self text html") {
      RedditJsonParser.decodeRedditHtml("&gt;&lt;&amp;") shouldBe "><&"
    }

  }

}
