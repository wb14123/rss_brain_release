package me.binwang.rss.sourcefinder

import org.scalatest.BeforeAndAfterEach
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class RsshubRulesConverterSpec extends AnyFunSpec with BeforeAndAfterEach with Matchers {

  describe("RSSHub rules converter") {
    it("should convert rules js file to json string") {
      val jsonStr = scala.io.Source.fromResource(RsshubRulesConverter.RADAR_RULES_JSON_FILE).mkString
      val result = RsshubRulesConverter.parseJson(jsonStr)
      result.size should be > 0
    }
  }

}
