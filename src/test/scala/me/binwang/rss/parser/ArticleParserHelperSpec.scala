package me.binwang.rss.parser

import org.scalatest.BeforeAndAfterEach
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import java.time.Month

class ArticleParserHelperSpec extends AnyFunSpec with BeforeAndAfterEach with Matchers {

  describe("article parser helper") {

    it("should parse single digit date time") {
      val time = ArticleParserHelper.parseTime("Thu, 2 Jun 2022 22:23:08 EDT").get
      time.getDayOfMonth shouldBe 2
      time.getMonth shouldBe Month.JUNE
      time.getYear shouldBe 2022
    }

    it("should trip html tags") {
      val desc = ArticleParserHelper.tripHtmlTags("<div><p>一二三</p><p>efg</p></div>", Some(15))
      desc shouldBe "<p>一二三</p>\n<p>e</p>"
    }

    it("should trip image tags for description") {
      val text = """<figure>
                   |  <div>
                   |    <img alt="A man walks past Presto machines underground in the TTC subway portals in Toronto." src="https://i.cbc.ca/1.6949397.1693223516!/cpImage/httpImage/image.jpg_gen/derivatives/16x9_780/ont-ttc-presto-20181209.jpg">
                   |  </div>
                   |  <figcaption>
                   |    The Toronto Transit Commission (TTC) says it is working to fix the fare vending machine outage 'as quickly as possible.'
                   |    (Nathan Denette/The Canadian Press)
                   |  </figcaption>
                   |</figure>
                   |<div>
                   |  <p>The Toronto Transit Commission (TTC) says it&nbsp;is investigating a system-wide PRESTO&nbsp;fare vending machine outage on Monday morning.</p>
                   |  <p>The transit agency reported the incident&nbsp;just before 6:20 a.m.</p>
                   |  <p>"We are investigating the cause and working to fix it as quickly as possible," the TTC said on X, formerly known as Twitter.</p>
                   |  <p>The outage does not affect tap payments for transit riders using the TTC, the transit agency said.</p>
                   |</div>""".stripMargin
      val desc = ArticleParserHelper.tripHtmlTags(text, Some(20))
      desc shouldBe "<p>The Toronto Trans</p>"
    }

  }

}
