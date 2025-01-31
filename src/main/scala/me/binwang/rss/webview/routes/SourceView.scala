package me.binwang.rss.webview.routes

import cats.effect.IO
import me.binwang.rss.model.{ArticleListLayout, ArticleOrder, FolderSource, FolderSourceMappingUpdater}
import me.binwang.rss.service.{SourceService, UserService}
import me.binwang.rss.webview.auth.CookieGetter.reqToCookieGetter
import me.binwang.rss.model.CirceEncoders._
import io.circe.generic.auto._
import me.binwang.rss.webview.basic.ContentRender.{hxSwapContentAttrs, wrapContentRaw}
import me.binwang.rss.webview.basic.ScalaTagAttributes.{hxExt, hxGet, hxPost, hxPushUrl, hxTrigger, is, xText}
import me.binwang.rss.webview.basic.{HttpResponse, ScalatagsSeqInstances}
import me.binwang.rss.webview.widgets.PageHeader
import org.http4s.circe._
import org.http4s.dsl.io._
import org.http4s.headers.`Content-Type`
import org.http4s.scalatags.ScalatagsInstances
import org.http4s.{EntityDecoder, HttpRoutes, MediaType}
import scalatags.Text.all._



class SourceView(sourceService: SourceService, userService: UserService) extends Http4sView with ScalatagsInstances
    with ScalatagsSeqInstances {

  implicit val sourceUpdaterEncoder: EntityDecoder[IO, FolderSourceMappingUpdater] = jsonOf[IO, FolderSourceMappingUpdater]

  private def reloadSourceAttrs(folderSource: FolderSource) = {
    hxSwapContentAttrs ++ Seq(hxGet := ArticleList.hxLinkFromSource(folderSource),
      hxPushUrl := s"/sources/${folderSource.source.id}/articles?in_folder=${folderSource.folderMapping.folderID}")
  }

  private def reloadSourceDom(folderSource: FolderSource): Frag = {
    div(reloadSourceAttrs(folderSource), hxTrigger := "load once")
  }

  override val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case req @ POST -> Root / "hx" / "sources" / sourceID / "copy_to_folder" =>
      val token = req.authToken
      val toFolderID = req.params("to_folder_id")
      val position = req.params("position").toLong
      sourceService.addSourceToFolder(token, toFolderID, sourceID, position).flatMap(r => Ok(r.toString))

    case req @ POST -> Root / "hx" / "sources" / sourceID / "delete_from_folder" =>
      val token = req.authToken
      val fromFolderID = req.params("from_folder_id")
      for {
        _ <- userService.removeCurrentFolderAndSource(token)
        r <- sourceService.delSourceFromFolder(token, fromFolderID, sourceID)
        res <- Ok(r.toString)
      } yield res

    case req @ POST -> Root / "hx" / "sources" / sourceID / "move_to_folder" =>
      val token = req.authToken
      val fromFolderID = req.params("from_folder_id")
      val toFolderID = req.params("to_folder_id")
      val position = req.params("position").toLong
      for {
        _ <- sourceService.addSourceToFolder(token, toFolderID, sourceID, position)
        _ <- sourceService.delSourceFromFolder(token, fromFolderID, sourceID)
        res <- Ok("OK")
      } yield res

    case req @ POST -> Root / "hx" / "sources" / sourceID / "unsubscribe" =>
      val token = req.authToken
      for {
        _ <- userService.removeCurrentFolderAndSource(token)
        r <- sourceService.delSourceForUser(token, sourceID)
        res <- Ok(r.toString)
      } yield res

    case req @ POST -> Root / "hx" / "folders" / folderID / "sources" / sourceID / "updateAndRefresh" =>
      val token = req.authToken
      for {
        updater <- req.as[FolderSourceMappingUpdater]
        _ <- sourceService.updateSourceMapping(token, sourceID, updater)
        fs <- sourceService.getSourceInFolder(token, folderID, sourceID)
        dom = reloadSourceDom(fs)
        res <- Ok(dom, `Content-Type`(MediaType.text.html))
      } yield res

    case req @ POST -> Root / "hx" / "folders" / folderID / "sources" / sourceID / "moveAfter" / targetSourceID =>
      val token = req.authToken
      for {
        _ <- sourceService.moveSourceAfter(token, folderID, sourceID, targetSourceID)
        res <- HttpResponse.hxRefresh
      } yield res

    case req @ POST -> Root / "hx" / "folders" / folderID / "sources" / sourceID / "moveBefore" / targetSourceID =>
      val token = req.authToken
      for {
        _ <- sourceService.moveSourceBefore(token, folderID, sourceID, targetSourceID)
        res <- HttpResponse.hxRefresh
      } yield res

    case req @ GET -> Root / "folders" / folderID / "sources" / sourceID / "edit" => wrapContentRaw(req) {
      val token = req.authToken
      sourceService.getSourceInUser(token, sourceID).flatMap { folderSource =>
        val dom = Seq(
          PageHeader(Some("Edit Feed")),
          form(
            cls := "form-body form-start",
            h2(cls := "form-row", folderSource.source.title.getOrElse[String]("Unknown")),
            folderSource.source.description.map(desc => small(cls := "form-row", desc)).getOrElse(""),
            small(cls := "form-block",
              div(span("Feed URL: "), a(href := folderSource.source.xmlUrl, target := "_blank")(folderSource.source.xmlUrl)),
              folderSource.source.htmlUrl.map { htmlUrl =>
                div(span("Website URL: "), a(href := htmlUrl, target := "_blank")(htmlUrl))
              }.getOrElse(""),
            ),
            small(cls := "form-block",
              folderSource.source.fetchCompletedAt.map { fetchCompleteAt =>
                val timestamp = fetchCompleteAt.toEpochSecond * 1000
                div(span("Last fetched at "), span(xText := s"new Date($timestamp).toLocaleString()"))
              }.getOrElse(""),
              div(span("Next fetched scheduled at "),
                span(xText := s"new Date(${folderSource.source.fetchScheduledAt.toEpochSecond * 1000}).toLocaleString()")),
            ),
            label(cls := "form-row", input(is := "boolean-checkbox", `type` := "checkbox", name := "showTitle",
              if (folderSource.folderMapping.showTitle) checked else ""), "Show Title"),
            label(cls := "form-row", input(is := "boolean-checkbox", `type` := "checkbox", name := "showFullArticle",
              if (folderSource.folderMapping.showFullArticle) checked else ""), "Show Full Content"),
            label(div("Order By"), select(name := "articleOrder",
              option(value := ArticleOrder.TIME.toString,
                if (folderSource.folderMapping.articleOrder == ArticleOrder.TIME) selected else "")("Time"),
              option(value := ArticleOrder.SCORE.toString,
                if (folderSource.folderMapping.articleOrder == ArticleOrder.SCORE) selected else "")("Score"),

            )),
            label(div("Article List Layout"), select(
              name := "articleListLayout",
              option(value := ArticleListLayout.LIST.toString,
                if (folderSource.folderMapping.articleListLayout == ArticleListLayout.LIST) selected else "")("List"),
              option(value := ArticleListLayout.GRID.toString,
                if (folderSource.folderMapping.articleListLayout == ArticleListLayout.GRID) selected else "")("Grid"),
              option(value := ArticleListLayout.SOCIAL_MEDIA.toString,
                if (folderSource.folderMapping.articleListLayout == ArticleListLayout.SOCIAL_MEDIA) selected else "")("Social Media"),
              option(value := ArticleListLayout.COMPACT.toString,
                if (folderSource.folderMapping.articleListLayout == ArticleListLayout.COMPACT) selected else "")("Compact"),
            )),
            div(cls := "button-row",
              button(cls := "secondary", reloadSourceAttrs(folderSource), "Cancel"),
              button(hxPost := s"/hx/folders/$folderID/sources/$sourceID/updateAndRefresh", hxExt := "json-enc", "Save"),
            ),
          ),
        )
        Ok(dom, `Content-Type`(MediaType.text.html))
      }
    }

  }
}
