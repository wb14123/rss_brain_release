package me.binwang.rss.parser

import cats.effect.unsafe.IORuntime
import cats.effect.{IO, Resource}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime
import java.util.UUID

class OPMLParserSpec extends AnyFunSpec with BeforeAndAfterEach with Matchers {

  implicit val ioRuntime: IORuntime = IORuntime.global
  val testFile = "/opml-test.xml"

  describe("OPML parser") {
    it(s"should parse $testFile") {
      val inputStream = Resource.make {
        IO(getClass.getResourceAsStream(testFile))
      } { i =>
        IO(i.close())
      }
      val userId = UUID.randomUUID().toString
      val (foldersWithSources, sources) = OPMLParser.parse(inputStream, userId, ZonedDateTime.now()).unsafeRunSync()
      foldersWithSources.length shouldBe 9
      sources.length shouldBe 1
      foldersWithSources.foreach { folderWithSource =>
        folderWithSource.folder.name.nonEmpty shouldBe true
        folderWithSource.sources.nonEmpty shouldBe true
        folderWithSource.sources.foreach { source =>
          source.xmlUrl.nonEmpty shouldBe true
        }
      }
    }
  }

}
