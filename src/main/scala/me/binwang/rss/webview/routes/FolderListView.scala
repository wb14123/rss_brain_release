package me.binwang.rss.webview.routes

import cats.effect.IO
import io.circe.generic.auto._
import me.binwang.rss.model.{ArticleListLayout, ArticleOrder, Folder, FolderCreator, FolderSource, FolderUpdater}
import me.binwang.rss.model.CirceEncoders._
import me.binwang.rss.service.{FolderService, SourceService, UserService}
import me.binwang.rss.webview.auth.CookieGetter.reqToCookieGetter
import me.binwang.rss.webview.basic.ContentRender.{hxSwapContentAttrs, wrapContentRaw}
import me.binwang.rss.webview.basic.HtmlCleaner.str2cleaner
import me.binwang.rss.webview.basic.{ContentRender, HttpResponse, ProxyUrl, ScalatagsSeqInstances}
import me.binwang.rss.webview.basic.ScalaTagAttributes.{hxTarget, _}
import me.binwang.rss.webview.routes.FolderListView.{folderSourcesDom, getFolderAndSources, reloadFolderDom}
import me.binwang.rss.webview.widgets.{PageHeader, SourceIcon}
import org.http4s.dsl.io._
import org.http4s.circe._
import org.http4s.headers._
import org.http4s.scalatags.ScalatagsInstances
import org.http4s.{EntityDecoder, HttpRoutes, MediaType}
import scalatags.Text.all.{button, _}

import java.net.URI
import scala.util.Try

object FolderListView {

  private val hideMenuScript = "if (smallScreen) showMenu = false; "

  private def sourceIconFromGoogle(urlOpt: Option[String]): String = {
    urlOpt.flatMap{ url => Try(new URI(url.toHttps.escapeHtml).getHost).toOption.map { domain =>
      s"https://www.google.com/s2/favicons?domain=$domain"
    }}.getOrElse("")
  }

  def sourceDom(fs: FolderSource, extraAttrs: Seq[AttrPair] = Seq()): Frag = {
    val sourceID = fs.source.id
    val folderID = fs.folderMapping.folderID
    val sourceName = fs.source.title.getOrElse("").escapeHtml
    // only show error messages when there are 3 errors in a row
    val errorHint: Frag = if (fs.source.fetchFailedMsg.isEmpty || fs.source.fetchErrorCount <= 3) "" else
      span(cls := "material-icons-outlined warning", title := fs.source.fetchFailedMsg.get)("error_outline")

    val sortBy = ArticleList.sortBy(fs.folderMapping.articleOrder)
    val layout = ArticleList.layout(fs.folderMapping.articleListLayout)
    div(
      cls := "source-name",
      extraAttrs,
      xInit := s"""setSourceImages('$sourceID', [
                  |  '${fs.source.iconUrl.map(_.toHttps.escapeHtml).getOrElse("")}',
                  |  '${fs.source.iconUrl.map(_.escapeHtml).getOrElse("")}',
                  |  '${ProxyUrl(fs.source.iconUrl.map(_.toHttps.escapeHtml).getOrElse(""))}',
                  |  '${sourceIconFromGoogle(fs.source.htmlUrl)}',
                  |])""".stripMargin,
      div(
        cls := "source-name-avatar",
        SourceIcon(sourceID),
        a(
          nullHref,
          id := s"source-menu-$folderID-$sourceID",
          xBind("class") := s"selectedSource === '$sourceID' ? 'selected' : ''",
          cls := "folder-menu-name source-menu",
          xOnClick := s"selectedSource = '$sourceID'; selectedFolder = null; $hideMenuScript",
          hxGet := s"/hx/sources/$sourceID/articles/$sortBy/$layout?title=${sourceName.encodeUrl}&in_folder=$folderID",
          hxPushUrl := s"/sources/$sourceID/articles?in_folder=$folderID",
          hxSwapContentAttrs,
          attr("pos") := fs.folderMapping.position,
          attr("folder-id") := folderID,
          attr("source-id") := fs.source.id,
        )(sourceName),
      ),
      div(hxPost := s"/hx/user/currentSource/$sourceID", hxSwap := "none",
        hxTrigger := s"click from:#source-menu-$sourceID"),
      errorHint,
    )
  }

  def folderSourcesDom(folder: Folder, sources: Seq[FolderSource]): Frag = {
    val sourceClass = if (folder.isUserDefault) "source-under-default-folder" else "source-under-folder"
    div(id := s"source-under-folder-${folder.id}", cls := sourceClass, xShow := "expanded",
      sources.map(s => sourceDom(s)))
  }

  def reloadFolderDom(folder: Folder): Frag = {
    div(hxSwapContentAttrs, hxTrigger := "load once", hxGet := ArticleList.hxLinkFromFolder(folder),
      hxPushUrl := s"/folders/${folder.id}/articles")
  }

  def getFolderAndSources(token: String, sourceService: SourceService,
      folderService: FolderService): IO[Seq[(Folder, Seq[FolderSource])]] = {
    for {
      sourcesWithFolders <- sourceService.getMySourcesWithFolders(token, 1024, -1).compile.toList
      sourcesMap = sourcesWithFolders
        .map { sf => (sf.folderMapping.folderID, sf) }
        .groupBy(_._1)
        .map{case (folderID, sources) => (folderID, sources.map(_._2))}
      folders <- folderService.getMyFolders(token, 1024, -1).compile.toList
      result = folders.map { folder =>
        val sources = sourcesMap.getOrElse(folder.id, Seq())
        (folder, sources)
      }
    } yield result
  }

}

class FolderListView(folderService: FolderService, sourceService: SourceService, userService: UserService) extends Http4sView
    with ScalatagsInstances with ScalatagsSeqInstances {

  private val hideMenuScript = "if (smallScreen) showMenu = false; "
  private val hxSwapContentAttrs = Seq(
    hxTarget := "#content",
    hxSwap := "innerHTML show:window:top",
    hxIndicator := "#content-indicator",
    hxSync := "#content:replace",
  )

  private def folderDom(folder: Folder, sources: Seq[FolderSource]): Frag = {
    val folderName = if (folder.isUserDefault) "All Articles" else folder.name.escapeHtml
    val hxLink = ArticleList.hxLinkFromFolder(folder)
    val sourceFetchErrors = sources.count(s => s.source.fetchFailedMsg.isDefined && s.source.fetchErrorCount > 3)
    val errorMsg = if (sourceFetchErrors == 1) "1 feed has problem" else s"$sourceFetchErrors feeds have problem"
    val errorHint: Frag = if (sourceFetchErrors == 0) "" else
      span(cls := "material-icons-outlined warning", title := errorMsg)("error_outline")
    val expandButton: Frag = if (folder.isUserDefault) "" else a(
      nullHref, cls := "material-icons-outlined folder-expand-icon cursor-enabled", xOnClick := "expanded=!expanded",
      span(xShow := "expanded", hxPost := s"/hx/folders/${folder.id}/update", hxVals:= "{\"expanded\": false}",
        hxSwap := "none", hxExt := "json-enc")("expand_less"),
      span(xShow := "!expanded", hxPost := s"/hx/folders/${folder.id}/update", hxVals:= "{\"expanded\": true}",
        hxSwap := "none", hxExt := "json-enc")("expand_more"),
    )

    val folderAttrs = Seq(
      nullHref,
      xBind("class") := s"selectedFolder === '${folder.id}' ? 'selected' : ''",
      cls := s"folder-menu-name",
      id := s"folder-menu-${folder.id}",
      xOnClick := s"selectedFolder = '${folder.id}'; selectedSource = null; $hideMenuScript",
      hxGet := hxLink, hxPushUrl:= s"/folders/${folder.id}/articles",
      attr("pos") := folder.position.toString,
      attr("folder-id") := folder.id,
    ) ++ hxSwapContentAttrs

    val folderRow = if (folder.isUserDefault) {
      div(cls := "side-menu-item default-folder", iconSpan("dynamic_feed"),
        a(cls := "default-folder-link", folderAttrs, "All Articles"))
    } else {
      div(
        cls := "folder-name-display",
        div(
          cls := "folder-name-with-expand",
          expandButton,
          a(cls := "folder-name-link", folderAttrs)(folderName),
        ),
        errorHint,
      )
    }

    div(
      cls := "folder-name", xData := s"{expanded: ${folder.expanded || folder.isUserDefault} }",
      folderRow,
      div(hxPost := s"/hx/user/currentFolder/${folder.id}", hxSwap := "none",
        hxTrigger := s"click from:#folder-menu-${folder.id}"),
      folderSourcesDom(folder, sources),
    )
  }

  private def sideMenuAttr(selectedMark: String, hxLink: String) = {
    Seq(
      xOnClick := s"selectedSource = '$selectedMark' ; selectedFolder = null; $hideMenuScript",
      xBind("class") := s"selectedSource === '$selectedMark' ? 'selected' : ''",
      hxGet := hxLink, hxPushUrl := "true"
    ) ++ hxSwapContentAttrs
  }

  private def sideMenu(): Seq[Frag] = Seq(
    div(cls := "side-menu-item", iconSpan("cloud_upload"), a(nullHref, "Import from OPML File",
      sideMenuAttr("menu-import-opml", "/import_opml"))),
    div(cls := "side-menu-item", iconSpan("create_new_folder"), a(nullHref, "Create New Folder",
      sideMenuAttr("menu-create-new-folder", "/folders/create"))),
    div(cls := "side-menu-item", iconSpan("add_circle_outline"), a(nullHref, "Add New Feed",
      sideMenuAttr("menu-import-new-feed", "/import_feed"))),
    div(cls := "side-menu-item", iconSpan("explore"), a(nullHref, "Explore",
      sideMenuAttr("menu-explore", "/explore"))),
    div(cls := "side-menu-item", iconSpan("settings"), a(nullHref, "Settings",
      sideMenuAttr("menu-settings", "/settings"))),
    div(cls := "side-menu-item", iconSpan("feedback"), a(nullHref, "Feedback",
      sideMenuAttr("menu-feedback", "/feedback"))),
  )

  implicit val folderUpdaterEncoder: EntityDecoder[IO, FolderUpdater] = jsonOf[IO, FolderUpdater]
  implicit val folderCreatorEncoder: EntityDecoder[IO, FolderCreator] = jsonOf[IO, FolderCreator]

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {


    case req @ GET -> Root / "hx" / "folders" =>
      val token = req.authToken
      val folderDoms = for {
        sourcesWithFolders <- getFolderAndSources(token, sourceService, folderService)
        folderDoms = sourcesWithFolders.map(e => folderDom(e._1, e._2))
        dom = sideMenu() ++ folderDoms
      } yield dom

      val foldersPrefix: Frag = raw(
        """<div id="folder-list"
          |x-data="{selectedFolder: folderFromUrl(),selectedSource: sourceFromUrl()}"
          |x-show="showMenu"
          |hx-get="/hx/folders"
          |hx-trigger="reload-folders"
          |hx-swap="outerHTML"
          |>""".stripMargin)
      val foldersSuffix: Frag = raw("</div>")
      Ok(fs2.Stream.emit(foldersPrefix) ++ fs2.Stream.evalSeq(folderDoms) ++ fs2.Stream.emit(foldersSuffix),
        `Content-Type`(MediaType.text.html))


    case req @ POST -> Root / "hx" / "folders" =>
      val token = req.authToken
      for {
        insertFolder <- req.as[FolderCreator]
        folder <- folderService.addFolder(token, insertFolder)
        redirectUrl = s"/folders/${folder.id}/articles"
        res <- HttpResponse.hxRedirect("", redirectUrl)
      } yield res


    case req @ DELETE -> Root / "hx" / "folders" / folderID =>
      val token = req.authToken
      for {
        _ <- userService.removeCurrentFolderAndSource(token)
        _ <- folderService.deleteFolder(token, folderID)
        res <- HttpResponse.hxRedirect("", "/")
      } yield res


    case req @ GET -> Root / "folders" / "create" => wrapContentRaw(req) {
      val dom = Seq(
        PageHeader(Some("Create New Folder")),
        form(
          cls := "form-body",
          label(div("Name"), input(`type` := "text", name := "name")),
          label(div("Description"), input(`type` := "text", name := "description")),
          div(cls := "button-row",
            button(hxPost := s"/hx/folders", hxExt := "json-enc",
              hxVals := "js:{position:getNextFolderPosition()}", "Create")),
        )
      )
      Ok(dom, `Content-Type`(MediaType.text.html))
    }

    case req @ POST -> Root / "hx" / "folders" / folderID / "update" =>
      val token = req.authToken
      req.as[FolderUpdater].flatMap { folderUpdater =>
        folderService.updateFolder(token, folderID, folderUpdater)
      }.flatMap(_ => Ok("OK"))

    case req @ POST -> Root / "hx" / "folders" / folderID / "updateAndRefresh" =>
      val token = req.authToken
      req.as[FolderUpdater].flatMap { folderUpdater =>
        folderService.updateFolder(token, folderID, folderUpdater)
      }.flatMap(folder => Ok(reloadFolderDom(folder)))

    case req @ POST -> Root / "hx" / "cleanupPosition" / "folders" =>
      val token = req.authToken
      folderService.cleanupPosition(token).flatMap(r => Ok(r.toString))

    case req @ GET -> Root / "folders" / folderID / "edit" => wrapContentRaw(req) {
      folderService.getFolderByID(req.authToken, folderID).flatMap { folder =>
        val dom = Seq(
          PageHeader(Some("Edit folder")),
          form(
            cls := "form-body form-start",
            label(div("Name"), input(`type` := "text", name := "name", value := folder.name)),
            label(div("Description"),
              input(`type` := "text", name := "description", value := folder.description.getOrElse(""))),
            label(cls := "form-row",
              input(is := "boolean-checkbox", `type` := "checkbox", name := "searchEnabled",
              if (folder.searchEnabled) checked else ""), "Enable Search",
            ),
            label(div("Search Term"),
              input(`type` := "text", name := "searchTerm", value := folder.searchTerm.getOrElse(""))),
            label(div("Order By"), select(name := "articleOrder",
              option(value := ArticleOrder.TIME.toString,
                if (folder.articleOrder == ArticleOrder.TIME) selected else "")("Time"),
              option(value := ArticleOrder.SCORE.toString,
                if (folder.articleOrder == ArticleOrder.SCORE) selected else "")("Score"),
            )),
            label(div("Article List Layout"), select(
              name := "articleListLayout",
              option(value := ArticleListLayout.LIST.toString,
                if (folder.articleListLayout == ArticleListLayout.LIST) selected else "")("Vertical List"),
              option(value := ArticleListLayout.GRID.toString,
                if (folder.articleListLayout == ArticleListLayout.GRID) selected else "")("Grid"),
            )),
            div(cls := "button-row",
              button(cls := "secondary", ContentRender.hxSwapContentAttrs,
                hxGet := ArticleList.hxLinkFromFolder(folder), hxPushUrl := s"/folders/$folderID/articles", "Cancel"),
              button(hxPost := s"/hx/folders/$folderID/updateAndRefresh", hxExt := "json-enc", "Save"),
            ),
          )
        )
        Ok(dom, `Content-Type`(MediaType.text.html))
      }
    }

    case req @ GET -> Root / "opml-export" =>
      val token = req.authToken
      folderService.exportOPML(token).flatMap { opml =>
        Ok(opml, `Content-Type`(MediaType.text.xml))
      }

  }
}
