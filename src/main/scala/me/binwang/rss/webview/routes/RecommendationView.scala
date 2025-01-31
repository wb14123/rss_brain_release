package me.binwang.rss.webview.routes
import cats.effect._
import me.binwang.rss.grpc.ModelTranslator
import me.binwang.rss.model.{MoreLikeThisType, UserInfo}
import me.binwang.rss.service.{ArticleService, FolderService, MoreLikeThisService, SourceService, UserService}
import me.binwang.rss.webview.auth.CookieGetter.reqToCookieGetter
import me.binwang.rss.webview.basic.ContentRender.{hxSwapContentAttrs, wrapContentRaw}
import me.binwang.rss.webview.basic.ScalaTagAttributes._
import me.binwang.rss.webview.basic.{HttpResponse, ScalatagsSeqInstances}
import me.binwang.rss.webview.widgets.{ArticleRender, PageHeader, SourcesPreview}
import me.binwang.rss.webview.widgets.ArticleRender.Horizontal
import me.binwang.rss.webview.widgets.TimeSelection._
import org.http4s.{HttpRoutes, MediaType}
import org.http4s.dsl.io._
import org.http4s.headers._
import org.http4s.scalatags.ScalatagsInstances
import org.typelevel.log4cats.LoggerFactory
import scalatags.Text.all._

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.{ZoneId, ZonedDateTime}

class RecommendationView(moreLikeThisService: MoreLikeThisService, articleService: ArticleService,
    sourceService: SourceService, folderService: FolderService, userService: UserService,
    )(implicit val loggerFactory: LoggerFactory[IO])
    extends Http4sView with ScalatagsInstances with ScalatagsSeqInstances {

  private val DEFAULT_SIZE = 10
  private val recommendationTimeSelectID = "recommendation-time-range-select"

  private def getTimeRange(rangeSeconds: Int): IO[(Long, Long)] = {
    IO.realTimeInstant.map { now =>
      ((now.getEpochSecond - rangeSeconds) * 1000, (now.getEpochSecond + rangeSeconds) * 1000)
    }
  }

  private def getTimeRangeStr(rangeSecondsStr: Option[String]): IO[String] = {
    val rangeSeconds = rangeSecondsStr.flatMap(_.toIntOption)
    if (rangeSeconds.isEmpty || rangeSeconds.get == 0) {
      IO.pure("")
    } else {
      getTimeRange(rangeSeconds.get).map { times =>
        s"?postedBefore=${times._2}&postedAfter=${times._1}"
      }
    }
  }

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case req @ GET -> Root / "hx" / "sources" / sourceID / "moreLikeThisSections" / articleID =>

      val token = req.authToken
      val timeParam = req.params.get("time_range")
      def timeRangeAttr(timeRange: String) = {
        Seq(
          timeSelected(timeRange, timeParam),
          Some(hxGet := s"${req.pathInfo}?time_range=$timeRange"),
          Some(hxTarget := "#recommendation-sections"),
          // listen on the select element to match the selected value
          Some(hxTrigger := s"change[target.value == '$timeRange'] from:#$recommendationTimeSelectID"),
          Some(value := timeRange),
          Some(hxSync := s"#$recommendationTimeSelectID:replace")
        )
      }
      val mappings = moreLikeThisService.getMoreLikeThisMappings(token, sourceID, MoreLikeThisType.SOURCE, 100, -1L)
      val sections = mappings.evalMap { mapping =>
        (mapping.moreLikeThisType match {
          case MoreLikeThisType.SOURCE => sourceService.getSource(token, mapping.moreLikeThisID)
            .map(s => s"In Feed: ${s.title.getOrElse("[Unknown Title]")}")
          case MoreLikeThisType.FOLDER => folderService.getFolderByID(token, mapping.moreLikeThisID)
            .map(f => s"In Folder: ${f.name}")
          case _ => IO.pure("In All Subscriptions")
        }).flatMap { sectionName =>
          getTimeRangeStr(timeParam).map { timeRangeStr =>
            val url = mapping.moreLikeThisType match {
              case MoreLikeThisType.SOURCE => s"/hx/articles/$articleID/moreLikeThis/source/${mapping.moreLikeThisID}$timeRangeStr"
              case MoreLikeThisType.FOLDER => s"/hx/articles/$articleID/moreLikeThis/folder/${mapping.moreLikeThisID}$timeRangeStr"
              case _ => s"/hx/articles/$articleID/moreLikeThis/all$timeRangeStr"
            }
            val deleteBaseUrl = s"/hx/sources/$sourceID/articles/$articleID/recommendations/delete"
            val deleteUrl = mapping.moreLikeThisType match {
              case MoreLikeThisType.SOURCE => s"$deleteBaseUrl?source=${mapping.moreLikeThisID}"
              case MoreLikeThisType.FOLDER => s"$deleteBaseUrl?folder=${mapping.moreLikeThisID}"
              case _ => deleteBaseUrl
            }
            div(
              cls := "recommendation-section",
              div(
                cls := "recommendation-section-title",
                sectionName,
                a(cls := "recommendation-delete-btn", nullHref, hxDelete := deleteUrl, iconSpan("delete")),
              ),
              div(hxGet := url, hxTrigger := "intersect once", hxTarget := "this", hxSwap := "outerHTML",
                hxIndicator := s"#recommend-indicator-${mapping.moreLikeThisID}"),
              tag("progress")(id := s"recommend-indicator-${mapping.moreLikeThisID}", cls := "htmx-indicator"),
            )
          }
        }
      }
      val title = div(cls := "recommendation-title", "More Articles Like This")
      val timeDom = div(
        cls := "recommendation-time-range form-row search-options",
        label("Time range"),
        select(
          id := recommendationTimeSelectID,
          option("All Time", timeRangeAttr("0")),
          option("24 hours around", timeRangeAttr(daySeconds)),
          option("1 week around", timeRangeAttr(weekSeconds)),
          option("1 month around", timeRangeAttr(monthSeconds)),
          option("1 year around", timeRangeAttr(yearSeconds)),
        )
      )
      val addButton = mappings.last.map(_.map(_.position).getOrElse(0L)).map { lastPosition =>
        val nextPosition = lastPosition + 1000L
        button(cls := "add-recommend-button", hxGet := s"/sources/$sourceID/articles/$articleID/recommendations/add?pos=$nextPosition",
          hxSwapContentAttrs, hxPushUrl := "true", "Add New Section")
      }
      val dom = fs2.Stream.emits(Seq(title, timeDom)) ++ sections ++ addButton
      Ok(dom, `Content-Type`(MediaType.text.html))


    case req @ GET -> Root / "sources" / sourceID / "articles" / articleID / "recommendations" / "add" => wrapContentRaw(req) {
      val token = req.authToken
      val pos = req.params.get("pos").flatMap(_.toLongOption).getOrElse(1000L)
      FolderListView.getFolderAndSources(token, sourceService, folderService).flatMap { folderSources =>
        val foldersDom = folderSources.map { case (folder, sources) =>
          div(
            xData := s"{expanded: ${folder.isUserDefault}}",
            cls := "recommendation-folder",
            div(
              cls := "folder-name-with-expand",
              if (folder.isUserDefault) "" else
                a(
                  nullHref, cls := "material-icons-outlined folder-expand-icon cursor-enabled", xOnClick := "expanded=!expanded",
                  span(xShow := "expanded")("expand_less"),
                  span(xShow := "!expanded")("expand_more"),
                ),
              if (folder.isUserDefault) {
                a(hxPost := s"/hx/sources/$sourceID/articles/$articleID/recommendations/add?pos=$pos",
                  nullHref, "All subscriptions")
              } else {
                a(hxPost := s"/hx/sources/$sourceID/articles/$articleID/recommendations/add?folder=${folder.id}&pos=$pos",
                  nullHref, folder.name)
              }
            ),
            div(
              xShow := "expanded",
              id := "add-recommendation-sources",
              if (folder.isUserDefault) cls := "add-recommendation-sources-default" else "",
              sources.map { source =>
                a(hxPost := s"/hx/sources/$sourceID/articles/$articleID/recommendations/add?source=${source.source.id}&pos=$pos",
                  nullHref, source.source.title.getOrElse[String]("Unknown source"))
              },
            )
          )
        }
        val doms = Seq(
          PageHeader(Some("Add New Recommendation Section")),
          div(
            cls := "form-body form-start",
            label("Click on an entry below to select a folder or a feed, or all subscriptions. " +
              "A recommendation section will be created to find articles from your choice."),
            div(cls := "recommendation-folders", foldersDom),
          )
        )
        Ok(doms, `Content-Type`(MediaType.text.html))
      }
    }


    case req @ POST -> Root / "hx" / "sources" / sourceID / "articles" / articleID / "recommendations" / "add" =>
      val token = req.authToken
      val pos = req.params.get("pos").flatMap(_.toLongOption).getOrElse(1000L)
      val addResult = if (req.params.contains("folder")) {
        moreLikeThisService.addMoreLikeThisMapping(token, sourceID, MoreLikeThisType.SOURCE, req.params("folder"),
          MoreLikeThisType.FOLDER, pos)
      } else if (req.params.contains("source")) {
        moreLikeThisService.addMoreLikeThisMapping(token, sourceID, MoreLikeThisType.SOURCE, req.params("source"),
          MoreLikeThisType.SOURCE, pos)
      } else {
        moreLikeThisService.addMoreLikeThisMapping(token, sourceID, MoreLikeThisType.SOURCE, "",
          MoreLikeThisType.ALL, pos)
      }
      addResult >> HttpResponse.redirect("added recommendation", s"/articles/$articleID", req)


    case req @ DELETE -> Root / "hx" / "sources" / sourceID / "articles" / articleID / "recommendations" / "delete" =>
      val token = req.authToken
      val delResult = if (req.params.contains("folder")) {
        moreLikeThisService.delMoreLikeThisMapping(token, sourceID, MoreLikeThisType.SOURCE, req.params("folder"),
          MoreLikeThisType.FOLDER)
      } else if (req.params.contains("source")) {
        moreLikeThisService.delMoreLikeThisMapping(token, sourceID, MoreLikeThisType.SOURCE, req.params("source"),
          MoreLikeThisType.SOURCE)
      } else {
        moreLikeThisService.delMoreLikeThisMapping(token, sourceID, MoreLikeThisType.SOURCE, "", MoreLikeThisType.ALL)
      }
      delResult >> HttpResponse.redirect("added recommendation", s"/articles/$articleID", req)


    case req @ GET -> Root / "hx" /  "articles" / articleID / "moreLikeThis" / "source" / sourceID =>
      val token = req.authToken
      val articles = articleService.moreLikeThisInSourceWithUserMarking(token, articleID, sourceID,
        req.params.get("start").flatMap(_.toIntOption).getOrElse(0),
        req.params.get("limit").flatMap(_.toIntOption).getOrElse(DEFAULT_SIZE),
        req.params.get("postedBefore").flatMap(_.toLongOption).map(t => ModelTranslator.longToDateTime(t)),
        req.params.get("postedAfter").flatMap(_.toLongOption).map(t => ModelTranslator.longToDateTime(t)),
      )
      val articlesDom = ArticleRender.renderList(articles, Horizontal())
      Ok(articlesDom, `Content-Type`(MediaType.text.html))


    case req @ GET -> Root / "hx" /  "articles" / articleID / "moreLikeThis" / "folder" / folderID=>
      val token = req.authToken
      val articles = articleService.moreLikeThisInFolderWithUserMarking(token, articleID, folderID,
        req.params.get("start").flatMap(_.toIntOption).getOrElse(0),
        req.params.get("limit").flatMap(_.toIntOption).getOrElse(DEFAULT_SIZE),
        req.params.get("postedBefore").flatMap(_.toLongOption).map(t => ModelTranslator.longToDateTime(t)),
        req.params.get("postedAfter").flatMap(_.toLongOption).map(t => ModelTranslator.longToDateTime(t)),
      )
      val articlesDom = ArticleRender.renderList(articles, Horizontal())
      Ok(articlesDom, `Content-Type`(MediaType.text.html))


    case req @ GET -> Root / "hx" /  "articles" / articleID / "moreLikeThis" / "all" =>
      val token = req.authToken
      val articles = articleService.moreLikeThisForUserWithUserMarking(token, articleID,
        req.params.get("start").flatMap(_.toIntOption).getOrElse(0),
        req.params.get("limit").flatMap(_.toIntOption).getOrElse(DEFAULT_SIZE),
        req.params.get("postedBefore").flatMap(_.toLongOption).map(t => ModelTranslator.longToDateTime(t)),
        req.params.get("postedAfter").flatMap(_.toLongOption).map(t => ModelTranslator.longToDateTime(t)),
      )
      val articlesDom = ArticleRender.renderList(articles, Horizontal())
      Ok(articlesDom, `Content-Type`(MediaType.text.html))

    case req @ GET -> Root / "explore" => wrapContentRaw(req) {
      val token = req.authToken
      folderService.getRecommendFolders(token, "en", 100, -1).compile.toList.flatMap { folders =>
        val folderDoms = folders.map { f =>
          button(cls := "explore-category secondary", hxGet := s"/explore/${f.id}/${f.name}", hxSwapContentAttrs,
            hxPushUrl := "true", f.name)
        }
        val dom = Seq(
          PageHeader(Some("Explore")),
          div(
            cls := "form-body form-start",
            label("Select a category to explore"),
            div(
              id := "explore-categories",
              folderDoms,
            ),
          )
        )
        Ok(dom, `Content-Type`(MediaType.text.html))
      }
    }

    case req @ GET -> Root / "explore" / folderID / folderName => wrapContentRaw(req) {
      val token = req.authToken
      val sources = sourceService.getSourcesInFolder(token, folderID, 100, -1).map(_.source)
      val dom = SourcesPreview(sources, "", PageHeader(Some(s"Explore: $folderName")), None)
      Ok(dom, `Content-Type`(MediaType.text.html))
    }

    case req @ GET -> Root / "articles" / articleID / "external-search" =>
      val token = req.authToken
      userService.getMyUserInfo(token).flatMap { user =>
        articleService.getArticleTermVector(token, articleID, 10).flatMap { terms =>
          val searchTerm = terms.terms.map(_.term).mkString(" ")
          val url = externalSearchUrl(user, searchTerm)
          HttpResponse.redirect("", url, req)
        }
      }

    case req @ GET -> Root / "folders" / folderID / "external-recommend" => wrapContentRaw(req) {
      val token = req.authToken
      for {
        now <- Clock[IO].realTimeInstant
        postAfter = ZonedDateTime.ofInstant(now, ZoneId.systemDefault()).minusWeeks(4)
        user <- userService.getMyUserInfo(token)
        links <- articleService.getFolderRecommendSearchTerms(token, folderID, 10, postAfter, 5)
          .map(Some(_)).handleError(_ => None)
        content: Frag = if (links.nonEmpty) {
          label(cls := "external-recommend-hint", "Choose one to search in external search engine") +:
          links.get.terms.map { term =>
            a(
              cls := "external-recommend-link",
              href := externalSearchUrl(user, term),
              target := "_blank",
              term,
            )
          }
        } else {
          div("LLM (large language model) is not configured. Go to ",
            a(nullHref, hxGet := "/settings", hxPushUrl := "true", hxSwapContentAttrs, "settings"),
            " to configure it."
          )
        }
        dom = Seq(
          PageHeader(Some("External Recommendation")),
          div(
            cls := "form-body form-start",
            content,
          )
        )
        res <- Ok(dom, `Content-Type`(MediaType.text.html))
      } yield res
    }
  }

  private def externalSearchUrl(user: UserInfo, term: String): String = {
    user.searchEngine.urlPrefix + URLEncoder.encode(term, StandardCharsets.UTF_8)
  }
}
