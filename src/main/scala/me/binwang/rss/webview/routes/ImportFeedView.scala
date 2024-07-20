package me.binwang.rss.webview.routes

import cats.effect.IO
import io.circe.generic.auto._
import me.binwang.rss.service.{FolderService, SourceService}
import me.binwang.rss.webview.auth.CookieGetter.reqToCookieGetter
import me.binwang.rss.webview.basic.ContentRender.{wrapContent, wrapContentRaw}
import me.binwang.rss.webview.basic.ScalaTagAttributes._
import me.binwang.rss.webview.basic.{HttpResponse, ScalatagsSeqInstances}
import me.binwang.rss.webview.widgets.{PageHeader, SourcesPreview}
import org.http4s.circe._
import org.http4s.dsl.io._
import org.http4s.headers.`Content-Type`
import org.http4s.multipart.Multipart
import org.http4s.scalatags.ScalatagsInstances
import org.http4s.{EntityDecoder, HttpRoutes, MediaType}
import org.typelevel.log4cats.LoggerFactory
import scalatags.Text.all._

class ImportFeedView(sourceService: SourceService, folderService: FolderService)(implicit val loggerFactory: LoggerFactory[IO])
    extends Http4sView with ScalatagsInstances with ScalatagsSeqInstances {

  private val logger = LoggerFactory.getLoggerFromClass[IO](this.getClass)

  case class SubscribeFeedReq(sourceID: String, folderID: String, pos: Long)
  implicit val folderUpdaterEncoder: EntityDecoder[IO, SubscribeFeedReq] = jsonOf[IO, SubscribeFeedReq]

  override val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case req @ GET -> Root / "import_feed" => wrapContent(req) {
      fs2.Stream.emits(Seq(
        PageHeader(Some("Add Feed")),
        div(
          id := "import-feed-form",
          cls := "form-body",
          small(cls := "import-feed-hint")("Input a RSS or Atom feed below. " +
            "You can also input any website URL, RSS Brain will try to find a feed for you."),
          input(id := "import-feed-input", `type` := "text", placeholder := "Feed or Website URL", name := "url"),
          button(hxGet := "/find_feed", hxTrigger := "click", hxTarget := "#content",
            hxIndicator := "#content-indicator", hxParamsAll, hxPushUrl := "true",
            hxInclude := "#import-feed-input")("Find"),
        )
      ))
    }

    case req @ GET -> Root / "import_opml" => wrapContentRaw(req) {
      val dom = Seq(
        PageHeader(Some("Import OPML File")),
        form(
          id := "import-opml-form",
          cls := "form-body",
          hxEncoding := "multipart/form-data",
          small(cls := "import-feed-hint")("Choose an OPML file to import"),
          input(name := "opml-file", `type` := "file"),
          button(hxPost := "/import_opml", hxTrigger := "click", hxTarget := "", hxDisableThis,
            hxIndicator := "#content-indicator", "Import")
        )
      )
      Ok(dom, `Content-Type`(MediaType.text.html))
    }

    case req @ POST -> Root / "import_opml" =>
      req.decode[Multipart[IO]] { multipart =>
          val inputStreamRes = fs2.io.toInputStreamResource(fs2.Stream.emits(multipart.parts).flatMap(_.body))
          folderService.importFromOPML(req.authToken, inputStreamRes).flatMap { _ =>
            HttpResponse.redirect("imported feeds", "/", req)
          }
      }


    case req @ GET -> Root / "find_feed" => wrapContent(req) {
      val token = req.authToken
      val url = req.params("url")
      val addToFolder = req.params.get("add_to_folder")
      val allUrls = fs2.Stream.emit(url) ++ sourceService.findSource(token, url).map(_.url)
      val sources = allUrls
        .parEvalMap(5)(url => logger.info(s"Try to verify source url $url") >>
          sourceService.getOrImportSource(token, url).map(Some(_))
            .handleErrorWith(e => logger.error(e)(s"Error to fetch source url $url").map(_ => None)))
        .filter(_.isDefined).map(_.get)
      SourcesPreview(sources, div(id := "no-feed-found-hint")(s"No feed found for url $url"),
        PageHeader(Some("Add Feed")), addToFolder)
    }


    case req @ POST -> Root / "hx" / "subscribe_feed" =>
      val token = req.authToken
      for {
        params <- req.as[SubscribeFeedReq]
        folderSource <- sourceService.addSourceToFolder(token, params.folderID, params.sourceID, params.pos)
        result = div(
          button(id := s"feed-subscribe-btn-${params.sourceID}", cls := "feed-subscribe-btn", disabled,
            hxSwapOob := "true")("Subscribed"),
          FolderListView.sourceDom(folderSource,
            Seq(hxSwapOob := s"beforeend:#source-under-folder-${params.folderID}"))
        )
        res <- Ok(result, `Content-Type`(MediaType.text.html))
      } yield res

  }
}
