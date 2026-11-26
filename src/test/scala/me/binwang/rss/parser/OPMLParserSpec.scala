package me.binwang.rss.parser

import cats.effect.unsafe.IORuntime
import cats.effect.{IO, Resource}
import me.binwang.rss.parser.OPMLParser.{FolderWithSources, ImportSource}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import java.io.{ByteArrayInputStream, InputStream}
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.util.UUID

class OPMLParserSpec extends AnyFunSpec with Matchers {

  implicit val ioRuntime: IORuntime = IORuntime.global
  private val testFile = "/opml-test.xml"
  private val userID = UUID.randomUUID().toString
  private val now = ZonedDateTime.now()

  private def parse(inputStream: Resource[IO, InputStream]): (Seq[FolderWithSources], Seq[ImportSource]) =
    OPMLParser.parse(inputStream, userID, now).unsafeRunSync()

  private def parse(content: String): (Seq[FolderWithSources], Seq[ImportSource]) =
    parse(streamOf(content))

  private def streamOf(content: String): Resource[IO, InputStream] = {
    Resource.make {
      IO(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)))
    } { i =>
      IO(i.close())
    }
  }

  private def opml(body: String): String = {
    s"""<?xml version="1.0" encoding="UTF-8"?>
       |<opml version="1.0">
       |  <head>
       |    <title>Test</title>
       |  </head>
       |  <body>
       |$body
       |  </body>
       |</opml>""".stripMargin
  }

  describe("OPML parser") {

    it(s"should parse $testFile") {
      val inputStream = Resource.make {
        IO(getClass.getResourceAsStream(testFile))
      } { i =>
        IO(i.close())
      }
      val (foldersWithSources, sources) = parse(inputStream)
      foldersWithSources.length shouldBe 9
      sources.length shouldBe 1
      foldersWithSources.foreach { folderWithSource =>
        folderWithSource.folder.name.nonEmpty shouldBe true
        folderWithSource.sources.nonEmpty shouldBe true
        folderWithSource.sources.foreach { importSource =>
          importSource.source.xmlUrl.nonEmpty shouldBe true
          importSource.name.nonEmpty shouldBe true
        }
      }
      val sourcesInFolders = foldersWithSources.flatMap(_.sources).map(_.source.id)
      (sourcesInFolders ++ sources.map(_.source.id)).distinct.length shouldBe 51
    }

    it("should use the text attribute as the feed name") {
      val (folders, sources) = parse(opml(
        """    <outline text="Text Name" title="Title Name" type="rss" xmlUrl="http://feed.example/one"/>"""))

      folders shouldBe empty
      sources.map(_.name) shouldBe Seq(Some("Text Name"))
    }

    it("should fall back to the title attribute when text is missing or blank") {
      val (folders, sources) = parse(opml(
        """    <outline title="Only Title" type="rss" xmlUrl="http://feed.example/one"/>
          |    <outline text="  " title="Title Wins" type="rss" xmlUrl="http://feed.example/two"/>""".stripMargin))

      folders shouldBe empty
      sources.map(_.name) shouldBe Seq(Some("Only Title"), Some("Title Wins"))
    }

    it("should trim the feed name and use no name when it is blank") {
      val (folders, sources) = parse(opml(
        """    <outline text="  Trimmed Name  " type="rss" xmlUrl="http://feed.example/one"/>
          |    <outline text="   " type="rss" xmlUrl="http://feed.example/two"/>""".stripMargin))

      folders shouldBe empty
      sources.map(_.name) shouldBe Seq(Some("Trimmed Name"), None)
    }

    it("should skip outlines with a blank or missing xmlUrl") {
      val (folders, sources) = parse(opml(
        """    <outline text="Folder" title="Folder">
          |      <outline text="Good Feed" type="rss" xmlUrl="http://feed.example/good"/>
          |      <outline text="Blank Url" type="rss" xmlUrl="   "/>
          |      <outline text="Missing Url" type="rss"/>
          |    </outline>
          |    <outline text="Top Level Without Url" type="rss"/>""".stripMargin))

      sources shouldBe empty
      folders.length shouldBe 1
      folders.head.sources.map(_.source.xmlUrl) shouldBe Seq("http://feed.example/good")
      folders.head.sources.map(_.name) shouldBe Seq(Some("Good Feed"))
    }

    it("should use the title attribute as the folder name with fallback to text") {
      val (folders, _) = parse(opml(
        """    <outline text="text name" title="Title Name">
          |      <outline text="Feed 1" type="rss" xmlUrl="http://feed.example/one"/>
          |    </outline>
          |    <outline text="Only Text Name">
          |      <outline text="Feed 2" type="rss" xmlUrl="http://feed.example/two"/>
          |    </outline>""".stripMargin))

      folders.map(_.folder.name) shouldBe Seq("Title Name", "Only Text Name")
      folders.map(_.sources.map(_.source.xmlUrl)) shouldBe Seq(
        Seq("http://feed.example/one"),
        Seq("http://feed.example/two"),
      )
    }

    it("should put the feeds of a folder without a name into the folderless sources") {
      val (folders, sources) = parse(opml(
        """    <outline text="   " title="">
          |      <outline text="Feed In Blank Folder" type="rss" xmlUrl="http://feed.example/one"/>
          |    </outline>""".stripMargin))

      folders shouldBe empty
      sources.map(_.source.xmlUrl) shouldBe Seq("http://feed.example/one")
      sources.map(_.name) shouldBe Seq(Some("Feed In Blank Folder"))
    }

    it("should deduplicate the same xmlUrl inside one folder") {
      val (folders, sources) = parse(opml(
        """    <outline text="Folder" title="Folder">
          |      <outline text="First Name" type="rss" xmlUrl="http://feed.example/dup"/>
          |      <outline text="Second Name" type="rss" xmlUrl="http://feed.example/dup"/>
          |    </outline>""".stripMargin))

      sources shouldBe empty
      folders.length shouldBe 1
      folders.head.sources.length shouldBe 1
      folders.head.sources.head.name shouldBe Some("First Name")
    }
  }

}
