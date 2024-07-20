package me.binwang.rss.parser

import me.binwang.rss.generator.Sources
import me.binwang.rss.model.ArticleOrder
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class SourcePropertyMatcherSpec extends AnyFunSpec with BeforeAndAfterEach with Matchers  {

  describe("source property matcher") {

    it("should update Twitter source properties") {
      val source = Sources.get().copy(htmlUrl = Some("https://twitter.com/Snowden"))
      val newSource = SourcePropertyMatcher.updateSourceWithMatchers(source)
      newSource.showTitle shouldBe false
      newSource.showMedia shouldBe true
      newSource.showFullArticle shouldBe true
      newSource.articleOrder shouldBe ArticleOrder.TIME
    }

    it("should update Weibo source properties") {
      Seq(
        Sources.get().copy(htmlUrl = Some("https://weibo.com/1578009030/")),
        Sources.get().copy(htmlUrl = Some("https://m.weibo.cn/u/1578009030/")),
      ).foreach{source =>
        val newSource = SourcePropertyMatcher.updateSourceWithMatchers(source)
        newSource.showTitle shouldBe false
        newSource.showMedia shouldBe true
        newSource.showFullArticle shouldBe true
        newSource.articleOrder shouldBe ArticleOrder.TIME
      }
    }

    it("should update hot Reddit source properties") {
      val source = Sources.get(maybeXmlUrl = Some("https://www.reddit.com/r/PoliticalHumor/hot.json?count=100"))
        .copy(htmlUrl= Some("https://www.reddit.com/r/WTFJustHappenedToday/"))
      val newSource = SourcePropertyMatcher.updateSourceWithMatchers(source)
      newSource.showTitle shouldBe true
      newSource.showMedia shouldBe true
      newSource.showFullArticle shouldBe false
      newSource.articleOrder shouldBe ArticleOrder.SCORE
    }

    it("should update HackerNews source properties") {
      val source = Sources.get().copy(htmlUrl= Some("https://news.ycombinator.com/news"))
      val newSource = SourcePropertyMatcher.updateSourceWithMatchers(source)
      newSource.showTitle shouldBe true
      newSource.showMedia shouldBe false
      newSource.showFullArticle shouldBe false
      newSource.articleOrder shouldBe ArticleOrder.SCORE
    }

  }

}
