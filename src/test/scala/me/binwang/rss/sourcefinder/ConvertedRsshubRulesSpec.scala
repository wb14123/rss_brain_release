package me.binwang.rss.sourcefinder

import cats.effect.unsafe.IORuntime
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class ConvertedRsshubRulesSpec extends AnyFunSpec with BeforeAndAfterEach with Matchers {

  implicit val ioRuntime: IORuntime = IORuntime.global
  private val sourceFinder = RegexSourceFinder("rsshub-regex-rules.json").unsafeRunSync()

  describe("Converted Rsshub Rules") {

    it("should match Bilibili") {
      sourceFinder.findSource("https://space.bilibili.com/427494870/video").unsafeRunSync().nonEmpty shouldBe true
    }

  }

}
