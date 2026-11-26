package me.binwang.rss.webview.routes

import cats.effect.IO
import me.binwang.rss.model.{ArticleWithUserMarking, CommentSourceNotSupported, CommentUrlMissing, ContentCompleteness}
import me.binwang.rss.service.{ArticleService, CommentService}
import me.binwang.rss.webview.auth.CookieGetter.reqToCookieGetter
import me.binwang.rss.webview.basic.ContentRender
import me.binwang.rss.webview.basic.ScalaTagAttributes._
import me.binwang.rss.webview.widgets.TimeSelection.weekSeconds
import me.binwang.rss.webview.widgets.{ArticleRender, CommentRender, PageHeader}
import org.http4s.dsl.io._
import org.http4s.headers._
import org.http4s.scalatags.ScalatagsInstances
import org.http4s.{HttpRoutes, MediaType}
import scalatags.Text.all._

class ArticleView(articleService: ArticleService, commentService: CommentService)
    extends Http4sView with ScalatagsInstances {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "articles" / articleID =>
      Ok(ContentRender(s"/hx/articles/$articleID"), `Content-Type`(MediaType.text.html))

    case req @ GET -> Root / "hx" / "articles" / articleID =>
      val token = req.authToken
      articleService.getFullArticleWithUserMarking(token, articleID).flatMap { articleWithMarking =>
        val article = articleWithMarking.article
        val contentLoadUrl =
          if (article.article.fullContentUrl.isDefined &&
              article.article.contentCompleteness != Some(ContentCompleteness.FULL))
            Some(s"/hx/articles/$articleID/content")
          else None
        val dom = div(
          id := "article-reader-wrapper",
          PageHeader(None),
          div(
            id := "article-reader-with-ops",
            ArticleRender.articleAttrs(articleWithMarking.userMarking, bindReadClass = false),
            div(
              id := "article-reader",
              xData := "{showComments: true}",
              div(cls := "article-title")(article.article.title),
              ArticleRender.renderInfo(article.article, req.params.get("in_folder")),
              ArticleRender.mediaDom(
                ArticleWithUserMarking(articleWithMarking.article.article, articleWithMarking.userMarking),
                ArticleRender.mediaRenderOptionInReader),
              ArticleRender.contentDom(article.content, contentLoadUrl),
              ArticleRender.renderOps(article.article, showActionable = false),
              if (article.article.commentUrl.isDefined) { Seq(
                a(nullHref, cls := "comment-op show-comment-btn", xShow := "showComments",
                  xOnClick := "showComments = false", "Hide comments"),
                a(nullHref, cls := "comment-op show-comment-btn", xShow := "!showComments",
                  xOnClick := "showComments = true", "Show comments"),
                div(xShow := "showComments",
                  div(
                    id := "comment-container",
                    hxGet := s"/hx/articles/$articleID/comments",
                    hxTrigger := "load",
                    hxSwap := "innerHTML",
                    hxOnResponseError := "this.innerHTML = ''",
                    hxOnSendError := "this.innerHTML = ''",
                    tag("progress")(id := "comment-progress"),
                  ),
                ),
              )} else "",
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

    case req @ GET -> Root / "hx" / "articles" / articleID / "content" =>
      articleService.getFullContent(req.authToken, articleID).flatMap { content =>
        Ok(ArticleRender.contentDom(content, None), `Content-Type`(MediaType.text.html))
      }

    case req @ GET -> Root / "hx" / "articles" / articleID / "comments" =>
      commentService.getComments(req.authToken, articleID)
        .attempt
        .flatMap {
          case Right(comments) => Ok(CommentRender.render(comments), `Content-Type`(MediaType.text.html))
          case Left(_: CommentUrlMissing) | Left(_: CommentSourceNotSupported) =>
            Ok(CommentRender.renderNoComments, `Content-Type`(MediaType.text.html))
          case Left(err) => IO.raiseError(err)
        }

    case req @ POST -> Root / "hx" / "articles" / articleID / "state" / state / value =>
      val token = req.authToken
      ((state, value) match {
        case ("bookmark", "true") => articleService.bookmarkArticle(token, articleID)
        case ("bookmark", "false") => articleService.unBookmarkArticle(token, articleID)
        case ("read", "true") => articleService.readArticle(token, articleID)
        case ("read", "false") => articleService.unreadArticle(token ,articleID)
        case ("read-progress", _) if value.toIntOption.isDefined =>
          articleService.markArticleReadProgress(token, articleID, value.toInt)
        case _ => IO.raiseError(new Exception("Wrong params"))
      }).flatMap(r => Ok(r.toString))

  }
}
