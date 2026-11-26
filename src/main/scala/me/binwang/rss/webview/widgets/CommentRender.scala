package me.binwang.rss.webview.widgets

import me.binwang.rss.model.{ArticleComments, Comment}
import me.binwang.rss.webview.basic.HtmlCleaner.str2cleaner
import me.binwang.rss.webview.basic.ScalaTagAttributes._
import scalatags.Text.all._

object CommentRender {

  // Must match the number of .comment-depth-* rules in main.css.
  private val depthColorCount = 8

  def render(comments: ArticleComments): Frag = {
    if (comments.comments.isEmpty) {
      raw("")
    } else {
      div(
        cls := "comments",
        comments.comments.map(renderComment(_, 0, comments.articleUrl)),
        if (comments.hasMore) commentOp(httpUrl(comments.articleUrl), "more comments") else "",
      )
    }
  }

  def renderNoComments: Frag = div(cls := "comment")("No comment to load")

  private def countDescendants(comment: Comment): Int = {
    comment.replies.map(reply => 1 + countDescendants(reply)).sum
  }

  private def httpUrl(url: String): Option[String] = {
    val trimmed = url.trim
    val lower = trimmed.toLowerCase
    if (lower.startsWith("http://") || lower.startsWith("https://")) Some(trimmed) else None
  }

  private def commentOp(url: Option[String], label: String): Frag = url match {
    case Some(link) => a(cls := "comment-op", href := link, target := "_blank")(label)
    case None => ""
  }

  private def renderComment(comment: Comment, depth: Int, articleUrl: String): Frag = {
    val depthClass = depth match {
      case 0 => "comment"
      case 1 => "comment comment-child"
      case _ => "comment comment-child comment-indent"
    }
    val colorClass = if (depth == 0) "" else s" comment-depth-${(depth - 1) % depthColorCount}"
    val author: String = comment.author.getOrElse("[Unknown user]")
    val expandLabel: String = if (comment.replies.isEmpty) "[show]" else s"[${countDescendants(comment)} more]"
    val permalink = comment.permalink.flatMap(httpUrl)
    div(
      cls := depthClass,
      xData := "{collapsed: false}",
      div(
        cls := s"comment-self$colorClass",
        div(
          cls := "comment-author-line",
          div(cls := "comment-author")(author),
          comment.score.map(score => div(cls := "comment-points")(s"$score points")),
          comment.createdAt.map(time => div(cls := "comment-time", xDate(time))),
          a(cls := "comment-op", nullHref, xShow := "collapsed", xOnClick := "collapsed = false")(expandLabel),
        ),
        div(cls := "comment-content", xShow := "!collapsed")(raw(comment.body.relaxedHtml)),
        div(
          cls := "comment-ops",
          xShow := "!collapsed",
          permalink.map(url => a(cls := "comment-op", href := url, target := "_blank")("open")),
          if (comment.hasMoreReplies) {
            commentOp(permalink.orElse(httpUrl(articleUrl)), "more replies")
          } else "",
          a(cls := "comment-op", nullHref, xOnClick := "collapsed = true")("hide"),
        ),
      ),
      hr(cls := "comment-separator"),
      if (comment.replies.nonEmpty) {
        div(
          cls := "comments",
          xShow := "!collapsed",
          comment.replies.map(renderComment(_, depth + 1, articleUrl)),
        )
      } else "",
    )
  }

}
