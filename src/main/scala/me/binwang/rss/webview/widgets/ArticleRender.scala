package me.binwang.rss.webview.widgets

import cats.effect.IO
import me.binwang.rss.model.{Article, ArticleUserMarking, ArticleWithUserMarking, MediaGroup, MediaMedium}
import me.binwang.rss.webview.basic.HtmlCleaner.str2cleaner
import me.binwang.rss.webview.basic.ProxyUrl
import me.binwang.rss.webview.basic.ScalaTagAttributes._
import scalatags.generic
import scalatags.Text.all._
import scalatags.text.Builder

object ArticleRender {

  sealed trait ArticleRenderLayout
  case class VerticalLayout() extends ArticleRenderLayout
  case class HorizontalLayout() extends ArticleRenderLayout
  case class GridLayout() extends ArticleRenderLayout

  case class MediaRenderOption(
      showAudio: Boolean,
      showVideoThumbnail: Boolean,
      showVideoPlayer: Boolean,
      showImage: Boolean,
  )

  val mediaRenderOptionInList: MediaRenderOption = MediaRenderOption(
    showAudio = false, showVideoThumbnail = true, showVideoPlayer = false, showImage = true)
  val mediaRenderOptionInReader: MediaRenderOption = MediaRenderOption(
    showAudio = true, showVideoThumbnail = false, showVideoPlayer = true, showImage = false)

  private def renderAudio(mediaGroup: MediaGroup): Frag = {
    div(
      cls := "article-audio-player",
      mediaGroup.thumbnails.headOption.map(t => proxyImage(t.url, None)),
      audio(attr("controls").empty, src := mediaGroup.content.url),
    )
  }

  private def proxyImage(url: String, desc: Option[String]): Frag = {
    img(
      src := s"${ProxyUrl(url.escapeHtml)}",
      alt := s"${desc.getOrElse("").escapeHtml}")
  }

  private def mediaGroupDom(mediaGroup: MediaGroup, option: MediaRenderOption): Frag = {
    mediaGroup.content.medium match {
      case None => ""
      case Some(MediaMedium.IMAGE) if option.showImage =>
        proxyImage(mediaGroup.content.url, mediaGroup.description)
      case Some(MediaMedium.VIDEO) if option.showVideoThumbnail && mediaGroup.thumbnails.nonEmpty  =>
        proxyImage(mediaGroup.thumbnails.head.url, mediaGroup.description)
      case Some(MediaMedium.VIDEO) if option.showVideoPlayer =>
        YoutubeVideoPlayer(mediaGroup.content.url)
      case Some(MediaMedium.AUDIO) if option.showAudio =>
        renderAudio(mediaGroup)
      case _ => ""
    }
  }

  def mediaDom(article: Article, option: MediaRenderOption = mediaRenderOptionInList): Frag = {
    article.mediaGroups.map(_.groups.head) match {
      case None => ""
      case _ => div(cls := "article-media", article.mediaGroups.get.groups.map(m => mediaGroupDom(m, option)))
    }
  }

  private def articleNumDom(num: Option[Int], divCls: String, icon: String): Frag =
    if (num.getOrElse(0) == 0) "" else div(cls := s"$divCls icon-info", iconSpan(icon), div(num.get))
  private def upvotesDom(article: Article): Frag = articleNumDom(article.upVotes, "article-upvotes", "thumb_up")
  private def downvotesDom(article: Article): Frag = articleNumDom(article.downVotes, "article-downvotes", "thumb_down")
  private def commentsDom(article: Article): Frag = articleNumDom(article.comments, "article-comments", "mode_comment")

  def renderInfo(article: Article): Frag = {
    val author = if (article.author.isEmpty) "" else s" by ${article.author.get.escapeHtml}"
    div(
      cls := "article-info",
      SourceIcon(article.sourceID),
      span(s"Published in ${article.sourceTitle.getOrElse("Unknown")}$author | "),
      span(cls := "material-icons-outlined")("schedule"),
      span(xText := s"new Date(${article.postedAt.toEpochSecond * 1000}).toLocaleString()")
    )
  }

  def articleAttrs(userMarking: ArticleUserMarking, bindReadClass: Boolean): Seq[generic.AttrPair[Builder, String]] = {
    val bindClass = attr("x-bind:class") := "read?'article-read':''"
    val data = xData := s"{read: ${userMarking.read}, bookmarked: ${userMarking.bookmarked}}"
    if (bindReadClass) {
      Seq(bindClass, data)
    } else {
      Seq(data)
    }
  }

  def renderOps(article: Article, showActionable: Boolean = true, showNonActionable: Boolean = true): Frag = {
    div(
      cls := "article-ops",
      if (showActionable) Seq(
        a(title := "Open original article", target := "_blank", href := article.link,
          cls := s"article-open-btn article-${article.id}-click", xOnClick := "read=true",
          iconSpan("open_in_new")),
        a(title := "Mark article as read", xShow := "!read", xOnClick := "read=true",
          hxPost := s"/hx/articles/${article.id}/state/read/true", hxSwap := "none", nullHref,
          iconSpan("check_circle")),
        a(title := "Mark article as unread", xShow := "read", xOnClick := "read=false",
          hxPost := s"/hx/articles/${article.id}/state/read/false", hxSwap := "none", nullHref,
          iconSpan("check_circle", iconCls = "material-icons")),
        a(title := "Like article", xShow := "!bookmarked", xOnClick := "bookmarked=true",
          hxPost := s"/hx/articles/${article.id}/state/bookmark/true", hxSwap := "none", nullHref,
          iconSpan("favorite_border")),
        a(title := "Unlike article", xShow := "bookmarked", xOnClick := "bookmarked=false",
          hxPost := s"/hx/articles/${article.id}/state/bookmark/false", hxSwap := "none", nullHref,
          iconSpan("favorite")),
      ) else Seq[Frag](),
      if (showNonActionable) Seq(
        upvotesDom(article),
        downvotesDom(article),
        commentsDom(article),
      ) else Seq[Frag](),
    )
  }


  def render(articleWithMarking: ArticleWithUserMarking, layout: ArticleRenderLayout): Frag = {
    val article = articleWithMarking.article
    val readAttr = articleAttrs(articleWithMarking.userMarking, bindReadClass = true)

    // delay 20ms so that other requests/changes will be made before the content is swapped out
    val clickAttr = Seq(
      nullClick,
      hxGet := s"/hx/articles/${article.id}",
      hxPushUrl := s"/articles/${article.id}",
      hxTarget := "#content",
      hxSwap := "innerHTML show:window:top",
      hxIndicator := "#content-indicator",
      hxSync := "#content:replace",
      hxTrigger := "click delay:20ms target:*:not(a)",
      xOnClick := "read=true"
    )

    val clickClasses = s"cursor-enabled article-${article.id}-click"
    val markReadDom = div(hxPost :=s"/hx/articles/${article.id}/state/read/true", hxSwap := "none",
      hxTrigger := s"click from:.article-${article.id}-click")

    val articleInfo = renderInfo(article)
    val articleOps = renderOps(article)
    val articleTitle = div(cls := s"article-title $clickClasses", clickAttr)(article.title)
    if (!layout.isInstanceOf[GridLayout]) {
      val directionClass = if (layout.isInstanceOf[VerticalLayout]) "article-vertical" else "article-horizontal"
      div(
        id := s"article-${article.id}",
        cls := s"article $directionClass",
        readAttr,
        markReadDom,
        articleTitle,
        articleInfo,
        mediaDom(article),
        div(cls := s"article-desc $clickClasses", clickAttr)(raw(article.description.validHtml)),
        articleOps,
      )
    } else {
      div(
        cls := "article article-grid",
        readAttr,
        mediaDom(article),
        div(
          cls := "article-card-content",
          articleTitle,
          articleInfo,
          markReadDom,
          articleOps,
        )
      )
    }
  }

  def renderList(articles: fs2.Stream[IO, ArticleWithUserMarking], layout: ArticleRenderLayout = VerticalLayout(),
      nextPageUrl: Option[Article => String] = None): fs2.Stream[IO, Frag] = {

    val articlesDom = if (nextPageUrl.isEmpty) {
      articles.map(a => render(a, layout))
    } else {
      articles.zipWithNext.flatMap { case (article, nextArticleOpt) =>
        val articleDom = render(article, layout)
        if (nextArticleOpt.isDefined) {
          fs2.Stream.emit(articleDom)
        } else {
          // TODO: different progress bar style for horizontal list
          // if it's the next page, only select .article instead of append the outer list div
          val nextPageDom = div(cls := "article", hxGet := nextPageUrl.get(article.article), hxTarget := "this",
            hxSwap := "outerHTML", hxTrigger := "intersect once", hxIndicator := "#nextpage-indicator",
            hxSelect := ".article", tag("progress")(id := "nextpage-indicator", cls := "htmx-indicator"))
          fs2.Stream.emits(Seq(articleDom, nextPageDom))
        }
      }
    }

    val listClass = layout match {
      case _: VerticalLayout => "article-list-vertical"
      case _: HorizontalLayout => "article-list-horizontal"
      case _: GridLayout => "article-list-grid"
    }
    val header: Frag = raw(s"""<div class="$listClass">""")
    val tail: Frag = raw("</div>")
    fs2.Stream.emit(header) ++ articlesDom ++ fs2.Stream.emit(tail)
  }

}
