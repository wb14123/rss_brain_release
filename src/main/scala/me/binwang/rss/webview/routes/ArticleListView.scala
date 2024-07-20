package me.binwang.rss.webview.routes

import cats.effect.IO
import cats.effect.kernel.Clock
import me.binwang.rss.grpc.ModelTranslator
import me.binwang.rss.model.ArticleListLayout.ArticleListLayout
import me.binwang.rss.model.ArticleOrder.ArticleOrder
import me.binwang.rss.model._
import me.binwang.rss.service.{ArticleService, FolderService, SourceService, UserService}
import me.binwang.rss.webview.auth.CookieGetter.reqToCookieGetter
import me.binwang.rss.webview.basic.ContentRender
import me.binwang.rss.webview.basic.ContentRender.{hxSwapContentAttrs, wrapContentRaw}
import me.binwang.rss.webview.basic.ErrorHandler.RedirectDefaultFolderException
import me.binwang.rss.webview.basic.HtmlCleaner.str2cleaner
import me.binwang.rss.webview.basic.ScalaTagAttributes._
import me.binwang.rss.webview.routes.ArticleList._
import me.binwang.rss.webview.widgets.ArticleRender.{ArticleRenderLayout, GridLayout, HorizontalLayout, VerticalLayout}
import me.binwang.rss.webview.widgets.{ArticleRender, EditFolderButton, EditSourceButton, PageHeader, SearchBox}
import org.http4s.dsl.io._
import org.http4s.headers._
import org.http4s.scalatags.ScalatagsInstances
import org.http4s.{HttpRoutes, MediaType, Request, Response}
import scalatags.Text.all._

import java.time.{ZoneId, ZonedDateTime}

object ArticleList {
  def sortBy(articleOrder: ArticleOrder): String = articleOrder match {
    case ArticleOrder.TIME => "by_time"
    case ArticleOrder.SCORE => "by_score"
  }

  def layout(layout: ArticleListLayout): String = layout match {
    case ArticleListLayout.LIST => "list"
    case ArticleListLayout.GRID => "grid"
  }

  def mapToQueryStr(m: Map[String, String]): String = {
    m.map {
      case (k, "") => s"${k.encodeUrl}"
      case (k, v) => s"${k.encodeUrl}=${v.encodeUrl}"
    }.mkString("&")
  }

  def mapOptToQueryStr(m: Map[String, Option[String]]): String = {
    mapToQueryStr(m.filter(_._2.isDefined).map(e => (e._1, e._2.get)))
  }

  def hxLinkFromFolder(folder: Folder): String = {
    val folderID = folder.id
    val folderName = folder.name.encodeUrl
    if (folder.isUserDefault) {
      s"/hx/articles?title=All Articles"
    } else if (folder.searchEnabled && folder.searchTerm.isDefined) {
      s"/folders/$folderID/articles/search/${layout(folder.articleListLayout)}?title=$folderName&by_time&q=${folder.searchTerm.get}"
    } else {
      s"/hx/folders/$folderID/articles/${sortBy(folder.articleOrder)}/${layout(folder.articleListLayout)}?title=$folderName"
    }
  }

  def hxLinkFromSource(fs: FolderSource): String = {
    val newParams = Map(
      "title" -> fs.source.title.getOrElse("Unknown Feed"),
      "in_folder" -> fs.folderMapping.folderID,
    )
    s"/hx/sources/${fs.folderMapping.sourceID}/articles/${sortBy(fs.folderMapping.articleOrder)}" +
      s"/${layout(fs.folderMapping.articleListLayout)}?${mapToQueryStr(newParams)}"
  }

}

class ArticleListView(articleService: ArticleService, userService: UserService, folderService: FolderService,
    sourceService: SourceService) extends Http4sView with ScalatagsInstances {

  private val LIST_SIZE = 20

  private def timeParam(timestamp: Option[String]): Option[ZonedDateTime] = {
    timestamp.flatMap(_.toLongOption).map(ModelTranslator.longToDateTime)
  }

  private def timeParamOrNow(timestamp: Option[String]): IO[ZonedDateTime] = {
    Clock[IO].realTimeInstant.map { nowInstant =>
      val now = ZonedDateTime.ofInstant(nowInstant, ZoneId.systemDefault())
      timeParam(timestamp).getOrElse(now)
    }
  }

  private def timeFromRange(rangeSeconds: Option[Int]): IO[Option[ZonedDateTime]] = {
    Clock[IO].realTimeInstant.map { nowInstant =>
      val now = ZonedDateTime.ofInstant(nowInstant, ZoneId.systemDefault())
      rangeSeconds.map(s => now.minusSeconds(s))
    }
  }

  private def layoutFromStr(str: String): ArticleRenderLayout = str match {
    case "list" => VerticalLayout()
    case "grid" => GridLayout()
    case "horizontal" => HorizontalLayout()
    case _ => VerticalLayout()
  }



  private def articlesWithHeader(req: Request[IO], showFilters: Boolean, editButton: Option[Frag],
      searchLink: Option[String]): Frag = {
    val headerTitle = req.params.getOrElse("title", "")
    val bookmarkFilter = req.params.contains("bookmarked")
    val readFilter = req.params.contains("read")
    val reqPath = req.pathInfo.toString()
    val opsAttrsWithoutTarget = Seq(hxIndicator := "#content-indicator", nullHref, hxTrigger := "click")
    val opsAttrs = opsAttrsWithoutTarget ++ Seq(hxTarget := "#article-list-wrapper", hxSwap := "outerHTML show:window:top")
    val bookmarkButton: Frag = if (!showFilters) "" else if (bookmarkFilter) {
      val nextLink = reqPath + "?" + mapToQueryStr(req.params - "bookmarked")
      a(title := "Remove bookmarked filter", hxGet := nextLink, opsAttrs, iconSpan("favorite"))
    } else {
      val nextLink = reqPath + "?" + mapToQueryStr(req.params + ("bookmarked" -> ""))
      a(title := "Filter bookmarked", hxGet := nextLink, opsAttrs, iconSpan("favorite_border"))
    }
    val readButton: Frag = if (!showFilters) "" else if (readFilter) {
      val nextLink = reqPath + "?" + mapToQueryStr(req.params - "read")
      a(title := "Remove read filter", hxGet := nextLink, opsAttrs, iconSpan("check_circle", iconCls = "material-icons"))
    } else {
      val nextLink = reqPath + "?" + mapToQueryStr(req.params + ("read" -> ""))
      a(title := "Filter already read", hxGet := nextLink, opsAttrs, iconSpan("check_circle"))
    }
    val searchButton = searchLink.map{link => a(title := "Search", nullHref, hxSwapContentAttrs, hxGet := link,
      hxPushUrl := "true", iconSpan("search"))}
    val articlesLink = reqPath + "?" + mapToQueryStr(req.params + ("noWrap" -> ""))
    val refreshButton = a(title := "Refresh article list", hxGet := articlesLink, opsAttrsWithoutTarget,
      hxTarget := "#article-list-loader", hxSwap := "innerHTML show:window:top", iconSpan("refresh"))
    div(id := "article-list-wrapper",
      PageHeader(Some(headerTitle)),
      div(id := "article-list-with-ops",
        div(id := "article-list-loader",
          div(hxGet := articlesLink, hxIndicator := "#content-indicator", hxTrigger := "load", hxSwap := "outerHTML")),
        div(id := "article-list-ops", searchButton.getOrElse(""), bookmarkButton, readButton, refreshButton, editButton.getOrElse(""))
      ),
    )
  }

  private def articleWrapperOr(req: Request[IO], showFilters: Boolean = true, editButton: Option[Frag] = None,
      searchLink: Option[String] = None)(orElse: => fs2.Stream[IO, Frag]): IO[Response[IO]] = {
    if (!req.params.contains("noWrap")) {
      Ok(articlesWithHeader(req, showFilters, editButton, searchLink), `Content-Type`(MediaType.text.html))
    } else {
      Ok(orElse, `Content-Type`(MediaType.text.html))
    }
  }

  private def getSearchOptions(req: Request[IO]): IO[SearchOptions] = {
    timeFromRange(req.params.get("time_range").flatMap(_.toIntOption)).map { timeFromRange =>
      SearchOptions(
        query = req.params("q"),
        start = req.params.get("start").flatMap(_.toIntOption).getOrElse(0),
        limit = LIST_SIZE,
        sortByTime = req.params.getOrElse("by_time", "false") != "false",
        highlight = req.params.contains("highlight"),
        postedAfter = timeParam(req.params.get("posted_after")).orElse(timeFromRange),
        postedBefore = timeParam(req.params.get("posted_before")),
      )
    }
  }

  private def nextPageSearchOptions(searchOptions: SearchOptions): SearchOptions = {
    searchOptions.copy(start = searchOptions.start + searchOptions.limit)
  }

  private def boolToOpt(b: Boolean): Option[String] = if (b) Some("") else None

  private def searchOptionsToUrlParams(searchOptions: SearchOptions, noWrap: Boolean = true): String = mapOptToQueryStr(Map(
    "q" -> Some(searchOptions.query),
    "start" -> Some(searchOptions.start.toString),
    "limit" -> Some(searchOptions.limit.toString),
    "by_time" -> boolToOpt(searchOptions.sortByTime),
    "highlight" -> boolToOpt(searchOptions.highlight),
    "posted_after" -> searchOptions.postedAfter.map(t => ModelTranslator.dateTimeToLong(t).toString),
    "posted_before" -> searchOptions.postedBefore.map(t => ModelTranslator.dateTimeToLong(t).toString),
  ) ++ (if (noWrap) Map("noWrap" -> Some("")) else Map()))

  private def baseAllSearchLink(): String = "/articles/search"
  private def allSearchLink(req: Request[IO]): String = s"/search?${mapToQueryStr(req.params)}"

  private def baseFolderSearchLink(folderID: String, layout: String): String =
    s"/folders/$folderID/articles/search/$layout"

  private def folderSearchLink(folderID: String, layout: String, req: Request[IO]): String = {
    s"/search?folderID=$folderID&layout=$layout&${mapToQueryStr(req.params)}"
  }

  private def baseSourceSearchLink(sourceID: String, layout: String): String =
    s"/sources/$sourceID/articles/search/$layout"

  private def sourceSearchLink(sourceID: String, layout: String, req: Request[IO]): String = {
    s"/search?sourceID=$sourceID&layout=$layout&${mapToQueryStr(req.params)}"
  }

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case req @ GET -> Root / "hx" / "articles" => articleWrapperOr(req, searchLink = Some(allSearchLink(req))) {
      val token = req.authToken
      fs2.Stream.eval(timeParamOrNow(req.params.get("postedBefore"))).flatMap { postedBefore =>
        val articlesStream = articleService.getMyArticlesWithUserMarking(token, LIST_SIZE,
          postedBefore,
          req.params.getOrElse("articleID", ""),
          req.params.get("read").map(_ => false),
          req.params.get("bookmarked").map(_ => true),
        )
        ArticleRender.renderList(articlesStream, VerticalLayout(), Some(article => {
          val postedBefore = ModelTranslator.dateTimeToLong(article.postedAt)
          val newParams = req.params ++ Map(
            "postedBefore" -> postedBefore.toString,
            "articleID" -> article.id,
            "noWrap" -> "",
          )
          s"/hx/articles?${mapToQueryStr(newParams)}"
        }))
      }
    }


    case req @ GET -> Root / "articles" / "search" => wrapContentRaw(req) {
      val showSearchBox = req.params.contains("showSearchBox")
      articleWrapperOr(req, showFilters = false) {
        fs2.Stream.eval(getSearchOptions(req)).flatMap { searchOptions =>
          val token = req.authToken
          val articlesStream = articleService.searchAllArticlesWithUserMarking(token, searchOptions)
          val nextSearchOption = nextPageSearchOptions(searchOptions)
          val baseLink = baseAllSearchLink()
          val searchBox: fs2.Stream[IO, Frag] = if (showSearchBox)
            fs2.Stream.emit(SearchBox(baseLink, req, Some(searchOptions))) else fs2.Stream.empty
          searchBox ++ ArticleRender.renderList(articlesStream, VerticalLayout(), Some(_ =>
            s"$baseLink?${searchOptionsToUrlParams(nextSearchOption)}"
          ))
        }
      }
    }


    case req @ GET -> Root / "sources" / sourceID / "articles" =>
      val token = req.authToken
      val folderSourceIO = req.params.get("in_folder")
        .map{folderID => sourceService.getSourceInFolder(token, folderID, sourceID)}
        .getOrElse(sourceService.getSourceInUser(token, sourceID))
        .recoverWith {
          case err @ (_: NoPermissionOnFolder | _: SourceNotFound | _: SourceInFolderNotFoundError) =>
            IO.raiseError(RedirectDefaultFolderException(err))
        }
      userService.setCurrentSource(token, sourceID) &>
        folderSourceIO.flatMap { folderSource =>
          val hxLink = hxLinkFromSource(folderSource)
          Ok(ContentRender(hxLink), `Content-Type`(MediaType.text.html))
        }


    case req @ GET -> Root / "hx" / "sources" / sourceID / "articles" / "by_time" / layout =>
      articleWrapperOr(req, editButton = EditSourceButton(sourceID, req.params.get("in_folder")),
          searchLink = Some(sourceSearchLink(sourceID, layout, req))) {
        val token = req.authToken
        fs2.Stream.eval(timeParamOrNow(req.params.get("postedBefore"))).flatMap { postedBefore =>
          val articlesStream = articleService.getArticlesBySourceWithUserMarking(
            token, sourceID, LIST_SIZE,
            postedBefore,
            req.params.getOrElse("articleID", ""),
            req.params.get("read").map(_ => false),
            req.params.get("bookmarked").map(_ => true),
          )
          ArticleRender.renderList(articlesStream, layoutFromStr(layout), Some(article => {
            val postedBefore = ModelTranslator.dateTimeToLong(article.postedAt)
            val newParams = req.params ++ Map(
              "postedBefore" -> postedBefore.toString,
              "articleID" -> article.id,
              "noWrap" -> "",
            )
            s"/hx/sources/$sourceID/articles/by_time/$layout?${mapToQueryStr(newParams)}"
          }))
        }
      }


    case req @ GET -> Root / "hx" / "sources" / sourceID / "articles" / "by_score" / layout =>
      articleWrapperOr(req, editButton = EditSourceButton(sourceID, req.params.get("in_folder")),
          searchLink = Some(sourceSearchLink(sourceID, layout, req))) {
        val token = req.authToken
        val articlesStream = articleService.getArticlesBySourceOrderByScoreWithUserMarking(token, sourceID, LIST_SIZE,
          req.params.get("maxScore").flatMap(_.toDoubleOption),
          req.params.getOrElse("articleID", ""),
          req.params.get("read").map(_ => false),
          req.params.get("bookmarked").map(_ => true),
        )
        ArticleRender.renderList(articlesStream, layoutFromStr(layout), Some(article => {
          val newParams = req.params ++ Map(
            "maxScore" -> article.score.toString,
            "articleID" -> article.id,
            "noWrap" -> "",
          )
          s"/hx/sources/$sourceID/articles/by_score/$layout?${mapToQueryStr(newParams)}"
        }))
      }


    case req @ GET -> Root / "sources" / sourceID / "articles" / "search" / layout => wrapContentRaw(req) {
      val showSearchBox = req.params.contains("showSearchBox")
      articleWrapperOr(req, showFilters = false,
          editButton = Some(EditSourceButton(sourceID, req.params.get("in_folder")))) {
        val token = req.authToken
        fs2.Stream.eval(getSearchOptions(req)).flatMap { searchOptions =>
          val articlesStream = articleService.searchArticlesBySourceWithUserMarking(token, sourceID, searchOptions)
          val nextSearchOption = nextPageSearchOptions(searchOptions)
          val baseLink = baseSourceSearchLink(sourceID, layout)
          val searchBox: fs2.Stream[IO, Frag] = if (showSearchBox)
            fs2.Stream.emit(SearchBox(baseLink, req, Some(searchOptions))) else fs2.Stream.empty
          searchBox ++ ArticleRender.renderList(articlesStream, layoutFromStr(layout), Some(_ =>
            s"$baseLink?${searchOptionsToUrlParams(nextSearchOption)}"
          ))
        }
      }
    }


    case req @ GET -> Root / "folders" / folderID / "articles" =>
      val token = req.authToken
      userService.setCurrentFolder(token, folderID) &>
        folderService.getFolderByID(token, folderID)
          .recoverWith{case err : NoPermissionOnFolder => IO.raiseError(RedirectDefaultFolderException(err))}
          .flatMap{ folder => Ok(ContentRender(hxLinkFromFolder(folder)), `Content-Type`(MediaType.text.html))}


    case req @ GET -> Root / "hx" / "folders" / folderID / "articles" / "by_time" / layout =>
      articleWrapperOr(req, editButton = Some(EditFolderButton(folderID)),
          searchLink = Some(folderSearchLink(folderID, layout, req))) {
        val token = req.authToken
        fs2.Stream.eval(timeParamOrNow(req.params.get("postedBefore"))).flatMap { postedBefore =>
          val articlesStream = articleService.getArticlesByFolderWithUserMarking(
            token, folderID, LIST_SIZE,
            postedBefore,
            req.params.getOrElse("articleID", ""),
            req.params.get("read").map(_ => false),
            req.params.get("bookmarked").map(_ => true),
          )
          ArticleRender.renderList(articlesStream, layoutFromStr(layout), Some(article => {
            val postedBefore = ModelTranslator.dateTimeToLong(article.postedAt)
            val newParams = req.params ++ Map(
              "postedBefore" -> postedBefore.toString,
              "articleID" -> article.id,
              "noWrap" -> "",
            )
            s"/hx/folders/$folderID/articles/by_time/$layout?${mapToQueryStr(newParams)}"
          }))
        }
      }


    case req @ GET -> Root / "hx" / "folders" / folderID / "articles" / "by_score" / layout =>
      articleWrapperOr(req, editButton = Some(EditFolderButton(folderID)),
          searchLink = Some(folderSearchLink(folderID, layout, req))) {
        val token = req.authToken
        val articlesStream = articleService.getArticlesByFolderOrderByScoreWithUserMarking(token, folderID, LIST_SIZE,
          req.params.get("maxScore").flatMap(_.toDoubleOption),
          req.params.getOrElse("articleID", ""),
          req.params.get("read").map(_ => false),
          req.params.get("bookmarked").map(_ => true),
        )
        ArticleRender.renderList(articlesStream, layoutFromStr(layout), Some(article => {
          val newParams = req.params ++ Map(
            "maxScore" -> article.score.toString,
            "articleID" -> article.id,
            "noWrap" -> "",
          )
          s"/hx/folders/$folderID/articles/by_score/$layout?${mapToQueryStr(newParams)}"
        }))
      }


    case req @ GET -> Root / "folders" / folderID / "articles" / "search" / layout => wrapContentRaw(req) {
      val showSearchBox = req.params.contains("showSearchBox")
      articleWrapperOr(req, showFilters = false, editButton = Some(EditFolderButton(folderID)),
          searchLink = if (showSearchBox) None else Some(folderSearchLink(folderID, layout, req))) {
        val token = req.authToken
        fs2.Stream.eval(getSearchOptions(req)).flatMap { searchOptions =>
          val articlesStream = articleService.searchArticlesByFolderWithUserMarking(token, folderID, searchOptions)
          val nextSearchOption = nextPageSearchOptions(searchOptions)
          val baseLink = baseFolderSearchLink(folderID, layout)
          val searchBox: fs2.Stream[IO, Frag] = if (showSearchBox)
            fs2.Stream.emit(SearchBox(baseLink, req, Some(searchOptions))) else fs2.Stream.empty
          searchBox ++ ArticleRender.renderList(articlesStream, layoutFromStr(layout), Some(_ =>
            s"$baseLink?${searchOptionsToUrlParams(nextSearchOption)}"
          ))
        }
      }
    }

    case req @ GET -> Root / "search" => wrapContentRaw(req) {
      val layout = req.params.get("layout")
      val folderIDOpt = req.params.get("folderID")
      val sourceIDOpt = req.params.get("sourceID")
      val baseLink = if (folderIDOpt.isDefined) {
        baseFolderSearchLink(folderIDOpt.get, layout.get)
      } else if (sourceIDOpt.isDefined) {
        baseSourceSearchLink(sourceIDOpt.get, layout.get)
      } else {
        baseAllSearchLink()
      }
      val dom = div(id := "article-list-wrapper",
        PageHeader(req.params.get("title")),
        SearchBox(baseLink, req),
      )
      Ok(dom, `Content-Type`(MediaType.text.html))
    }


  }
}
