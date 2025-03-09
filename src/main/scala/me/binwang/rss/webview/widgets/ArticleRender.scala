package me.binwang.rss.webview.widgets

import cats.effect.IO
import com.typesafe.config.ConfigFactory
import me.binwang.rss.model.ArticleListLayout.ArticleListLayout
import me.binwang.rss.model._
import me.binwang.rss.webview.basic.HtmlCleaner.str2cleaner
import me.binwang.rss.webview.basic.ScalaTagAttributes._
import me.binwang.rss.webview.basic.{ContentRender, ProxyUrl}
import scalatags.Text.all._
import scalatags.generic
import scalatags.text.Builder

object ArticleRender {


  sealed trait ArticleListDirection
  case class Vertical() extends ArticleListDirection
  case class Horizontal() extends ArticleListDirection

  case class MediaRenderOption(
      showAudio: Boolean,
      showVideoThumbnail: Boolean,
      showVideoPlayer: Boolean,
      showImage: Boolean,
      showImageInArticle: Boolean,
  )

  val mediaRenderOptionInList: MediaRenderOption = MediaRenderOption(
    showAudio = false, showVideoThumbnail = true, showVideoPlayer = false, showImage = true, showImageInArticle = true)
  val mediaRenderOptionInReader: MediaRenderOption = MediaRenderOption(
    showAudio = true, showVideoThumbnail = false, showVideoPlayer = true, showImage = true, showImageInArticle = false)

  private def renderAudio(article: ArticleWithUserMarking, mediaGroup: MediaGroup): Frag = {
    div(
      cls := "article-audio-player",
      mediaGroup.thumbnails.headOption.map(t => proxyImage(t.url, None, addVideoClass = false)),
      audio(
        attr("controls").empty,
        is := "audio-tracker",
        src := mediaGroup.content.url,
        attr("saved-progress") := article.userMarking.readProgress,
        attr("save-progress-url") := SaveProgressUrl(article.article.id)
      ),
    )
  }

  private def proxyImage(url: String, desc: Option[String], addVideoClass: Boolean): Frag = {
    val proxyUrl = ProxyUrl(url)
    img(
      is := "multi-imgs",
      attr("srcs") := s"$url $proxyUrl",
      attr("loading") := "lazy",
      alt := s"${desc.getOrElse("").escapeHtml.take(20)}",
      if (addVideoClass) cls := "video-img" else "",
    )
  }

  private def mediaGroupDom(article: ArticleWithUserMarking, mediaGroup: MediaGroup, option: MediaRenderOption): Frag = {
    mediaGroup.content.medium match {
      case None => ""
      case Some(MediaMedium.IMAGE) =>
        val fromArticle = mediaGroup.content.fromArticle.getOrElse(false)
        if ((fromArticle && option.showImageInArticle) || (!fromArticle && option.showImage)) {
          proxyImage(mediaGroup.content.url, mediaGroup.description, addVideoClass = false)
        } else ""
      case Some(MediaMedium.VIDEO) if option.showVideoThumbnail && mediaGroup.thumbnails.nonEmpty  =>
        proxyImage(mediaGroup.thumbnails.head.url, mediaGroup.description, addVideoClass = true)
      case Some(MediaMedium.VIDEO) if option.showVideoPlayer =>
        YoutubeVideoPlayer(article, mediaGroup.content.url)
      case Some(MediaMedium.AUDIO) if option.showAudio =>
        renderAudio(article, mediaGroup)
      case _ => ""
    }
  }

  def mediaDom(article: ArticleWithUserMarking, option: MediaRenderOption = mediaRenderOptionInList): Frag = {
    article.article.mediaGroups.map(_.groups.head) match {
      case None => ""
      case _ => tag("img-viewer")(cls := "article-media", article.article.mediaGroups.get.groups.map(
        m => mediaGroupDom(article, m, option)))
    }
  }

  private def articleNumDom(num: Option[Int], divCls: String, icon: String): Frag =
    if (num.getOrElse(0) == 0) "" else div(cls := s"$divCls icon-info", iconSpan(icon), div(num.get))
  private def upvotesDom(article: Article): Frag = articleNumDom(article.upVotes, "article-upvotes", "thumb_up")
  private def downvotesDom(article: Article): Frag = articleNumDom(article.downVotes, "article-downvotes", "thumb_down")
  private def commentsDom(article: Article): Frag = articleNumDom(article.comments, "article-comments", "mode_comment")

  def renderInfo(article: Article, folderIDOpt: Option[String]): Frag = {
    val author = if (article.author.isEmpty) "" else s" by ${article.author.get.escapeHtml}"
    val sourceUrl = s"/sources/${article.sourceID}/articles${getFolderParam(folderIDOpt)}"
    val sourceTitle = a(hxGet := sourceUrl, hxPushUrl := sourceUrl, ContentRender.hxSwapContentAttrs, nullHref)(
      article.sourceTitle.getOrElse[String]("Unknown"))
    div(
      cls := "article-info",
      SourceIcon(article.sourceID),
      span("Published in ", sourceTitle, s"$author | "),
      span(cls := "material-icons-outlined")("schedule"),
      span(xText := s"new Date(${article.postedAt.toEpochSecond * 1000}).toLocaleString()")
    )
  }

  private def renderSocialMediaInfo(article: Article, folderIDOpt: Option[String]): Frag = {
    val sourceUrl = s"/sources/${article.sourceID}/articles${getFolderParam(folderIDOpt)}"
    val sourceTitle = a(cls := "source-title", hxGet := sourceUrl, hxPushUrl := sourceUrl,
      ContentRender.hxSwapContentAttrs, nullHref)(article.sourceTitle.getOrElse[String]("Unknown"))
    div(
      cls := "article-info-social-media",
      SourceIcon(article.sourceID),
      div(cls := "social-media-info-details",
        div(sourceTitle),
        div(cls := "article-time", xText := s"new Date(${article.postedAt.toEpochSecond * 1000}).toLocaleString()")
      ),
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
        a(title := "Search External", href := s"/articles/${article.id}/external-search",
          target := "_blank", iconSpan("manage_search")),
      ) else Seq[Frag](),
      if (showNonActionable) Seq(
        upvotesDom(article),
        downvotesDom(article),
        commentsDom(article),
      ) else Seq[Frag](),
    )
  }


  def render(articleWithMarking: ArticleWithUserMarking, direction: ArticleListDirection,
      layout: ArticleListLayout, folderIDOpt: Option[String]): Frag = {
    val article = articleWithMarking.article
    val readAttr = articleAttrs(articleWithMarking.userMarking, bindReadClass = true)

    // delay 20ms so that other requests/changes will be made before the content is swapped out
    val clickAttr = Seq(
      nullClick,
      hxGet := s"/hx/articles/${article.id}${getFolderParam(folderIDOpt)}",
      hxPushUrl := s"/articles/${article.id}${getFolderParam(folderIDOpt)}",
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

    val articleInfo = if (layout == ArticleListLayout.SOCIAL_MEDIA) renderSocialMediaInfo(article, folderIDOpt)
      else  renderInfo(article, folderIDOpt)
    val articleOps = renderOps(article)
    val articleTitle = div(cls := s"article-title $clickClasses", clickAttr)(article.title)
    val nsfwClass = if (article.nsfw) "nsfw" else ""
    val layoutClass = s"article-${layout.toString.toLowerCase.replace("_", "-")}"
    if (layout != ArticleListLayout.GRID) {
      val directionClass = if (direction.isInstanceOf[Vertical]) "article-vertical" else "article-horizontal"
      div(
        id := s"article-${article.id}",
        cls := s"article $directionClass $nsfwClass $layoutClass",
        readAttr,
        markReadDom,
        if (layout != ArticleListLayout.SOCIAL_MEDIA) articleTitle else "",
        articleInfo,
        if (layout != ArticleListLayout.COMPACT) mediaDom(articleWithMarking) else "",
        if (layout != ArticleListLayout.COMPACT)
          div(cls := s"article-desc $clickClasses", clickAttr)(raw(article.description.validHtml)) else "",
        if (layout != ArticleListLayout.COMPACT && article.nsfw) {
          div(cls := s"article-desc-nsfw", clickAttr)("[NSFW Content]")
        } else "",
        articleOps,
      )
    } else {
      div(
        cls := s"article article-grid $nsfwClass",
        readAttr,
        mediaDom(articleWithMarking),
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

  def renderList(
      articles: fs2.Stream[IO, ArticleWithUserMarking],
      direction: ArticleListDirection = Vertical(),
      layout: ArticleListLayout = ArticleListLayout.LIST,
      nextPageUrl: Option[Article => String] = None,
      folderIDOpt: Option[String] = None,
    ): fs2.Stream[IO, Frag] = {

    val articlesDom = if (nextPageUrl.isEmpty) {
      articles.map(a => render(a, direction, layout, folderIDOpt))
    } else {
      articles.zipWithNext.flatMap { case (article, nextArticleOpt) =>
        val articleDom = render(article, direction, layout, folderIDOpt)
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

    val listClass = if (layout == ArticleListLayout.GRID) {
      "article-list-grid"
    } else {
      direction match {
        case _: Vertical => "article-list-vertical"
        case _: Horizontal => "article-list-horizontal"
      }
    }
    val header: Frag = raw(s"""<div class="$listClass">""")
    val tail: Frag = raw("</div>")
    fs2.Stream.emit(header) ++ articlesDom ++ fs2.Stream.emit(tail)
  }

  private def getFolderParam(folderIDOpt: Option[String]): String = {
    folderIDOpt.map(f => s"?in_folder=$f").getOrElse("")
  }

}
