package me.binwang.rss.webview.routes

import cats.effect.IO
import me.binwang.rss.service.ArticleService
import me.binwang.rss.webview.auth.CookieGetter.reqToCookieGetter
import me.binwang.rss.webview.basic.ContentRender
import me.binwang.rss.webview.basic.ScalaTagAttributes._
import me.binwang.rss.webview.basic.HtmlCleaner.str2cleaner
import me.binwang.rss.webview.widgets.TimeSelection.weekSeconds
import me.binwang.rss.webview.widgets.{ArticleRender, PageHeader}
import org.http4s.dsl.io._
import org.http4s.headers._
import org.http4s.scalatags.ScalatagsInstances
import org.http4s.{HttpRoutes, MediaType}
import scalatags.Text.all._

class ArticleView(articleService: ArticleService) extends Http4sView with ScalatagsInstances {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "articles" / articleID =>
      Ok(ContentRender(s"/hx/articles/$articleID"), `Content-Type`(MediaType.text.html))

    case req @ GET -> Root / "hx" / "articles" / articleID =>
      val token = req.authToken
      articleService.getFullArticleWithUserMarking(token, articleID).flatMap { articleWithMarking =>
        val article = articleWithMarking.article
        val dom = div(
          id := "article-reader-wrapper",
          PageHeader(None),
          div(
            id := "article-reader-with-ops",
            ArticleRender.articleAttrs(articleWithMarking.userMarking, bindReadClass = false),
            div(
              id := "article-reader",
              div(cls := "article-title")(article.article.title),
              ArticleRender.renderInfo(article.article, req.params.get("in_folder")),
              ArticleRender.mediaDom(article.article, ArticleRender.mediaRenderOptionInReader),
              div(cls := "article-content")(raw(article.content.validHtml)),
              ArticleRender.renderOps(article.article, showActionable = false),
              tag("somment-comment")(attr("link") := article.article.link),
              div(
                id := "recommendation-sections",
                div(hxTrigger := "intersect once",  hxTarget := "this", hxSwap := "outerHTML",
                  hxGet := s"/hx/sources/${article.article.sourceID}/moreLikeThisSections/$articleID?time_range=$weekSeconds"),
              ),
            ),
            ArticleRender.renderOps(article.article, showNonActionable = false),
          )
        )
        Ok(dom, `Content-Type`(MediaType.text.html))
      }

    case req @ POST -> Root / "hx" / "articles" / articleID / "state" / state / value =>
      val token = req.authToken
      val boolValue = value.toBooleanOption
      ((state, boolValue) match {
        case ("bookmark", Some(true)) => articleService.bookmarkArticle(token, articleID)
        case ("bookmark", Some(false)) => articleService.unBookmarkArticle(token, articleID)
        case ("read", Some(true)) => articleService.readArticle(token, articleID)
        case ("read", Some(false)) => articleService.unreadArticle(token ,articleID)
        case _ => IO.raiseError(new Exception("Wrong params"))
      }).flatMap(r => Ok(r.toString))

  }
}
