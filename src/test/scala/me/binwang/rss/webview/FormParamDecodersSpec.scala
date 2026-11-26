package me.binwang.rss.webview

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import me.binwang.rss.model.ArticleOrder
import me.binwang.rss.model.{FolderCreator, FolderSourceMappingUpdater, FolderUpdater}
import me.binwang.rss.webview.routes.{FolderListView, ImportFeedView, SourceView}
import org.http4s.headers.`Content-Type`
import org.http4s.{EntityDecoder, MediaType, Method, Request, Uri}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

class FormParamDecodersSpec extends AnyFunSpec with Matchers {

  implicit val ioRuntime: IORuntime = IORuntime.global
  implicit val loggerFactory: LoggerFactory[IO] = Slf4jFactory.create[IO]

  private def request(body: String): Request[IO] =
    Request[IO](Method.POST, Uri.unsafeFromString("/"))
      .withEntity(body)
      .withContentType(`Content-Type`(MediaType.application.json))

  private def decode[A](decoder: EntityDecoder[IO, A], body: String): Either[Any, A] = {
    decoder.decode(request(body), strict = false).value.unsafeRunSync()
  }

  describe("Form param decoders") {

    val folderListView = new FolderListView(null, null, null)

    it("should decode booleans and numbers sent as strings") {
      decode(folderListView.folderUpdaterEncoder,
        """{"name": "abc", "position": "3000", "expanded": "false", "articleOrder": "SCORE"}""") shouldBe
        Right(FolderUpdater(name = Some("abc"), position = Some(3000L), expanded = Some(false),
          articleOrder = Some(ArticleOrder.SCORE)))
    }

    it("should decode booleans and numbers sent as json values") {
      decode(folderListView.folderUpdaterEncoder, """{"position": 3000, "expanded": false}""") shouldBe
        Right(FolderUpdater(position = Some(3000L), expanded = Some(false)))
    }

    it("should decode folder creator") {
      decode(folderListView.folderCreatorEncoder, """{"name": "abc", "description": "desc", "position": "3000"}""") shouldBe
        Right(FolderCreator("abc", Some("desc"), 3000L))
    }

    it("should decode folder source mapping updater") {
      val sourceView = new SourceView(null, null)
      decode(sourceView.sourceUpdaterEncoder, """{"customSourceName": "name", "showTitle": "true"}""") shouldBe
        Right(FolderSourceMappingUpdater(customSourceName = Some(Some("name")), showTitle = Some(true)))
    }

    it("should decode subscribe feed request") {
      val importFeedView = new ImportFeedView(null, null)
      val body = """{"sourceID": "source", "folderID": "folder", "pos": "3000"}"""
      decode(importFeedView.folderUpdaterEncoder, body).map(r => (r.sourceID, r.folderID, r.pos)) shouldBe
        Right(("source", "folder", 3000L))
    }

  }

}
