package me.binwang.rss.webview.widgets

import cats.effect.IO
import me.binwang.rss.model.Source
import me.binwang.rss.webview.basic.ScalaTagAttributes._
import scalatags.Text.all._

object SourcesPreview {

  def apply(sources: fs2.Stream[IO, Source], emptyDom: Frag, header: Frag,
       addToFolderID: Option[String]): fs2.Stream[IO, Frag] = {
    val dom = sources.map { source =>
      val subscribedScript = s"document.querySelector(\".source-menu[source-id='${source.id}']\")"
      val folderID = addToFolderID.map(f => s"'$f''").getOrElse("getDefaultFolderID()");

      val subscribeVars =
        s"""js:{
           |  folderID: $folderID,
           |  sourceID: '${source.id}',
           |  pos: getNextPositionInFolder($folderID),
           |}
           |""".stripMargin.filter(_ >= ' ') // remove control chars likes space, tab, newline

      div(
        cls := "feed-preview",
        div (
          cls := "feed-preview-header",
          h2(cls := "feed-preview-title")(source.title.getOrElse[String]("Unknown")),
          button(xShow := s"$subscribedScript != null", cls := "feed-subscribe-btn", disabled)("Subscribed"),
          button(id := s"feed-subscribe-btn-${source.id}", cls := "feed-subscribe-btn",
            xShow := s"$subscribedScript == null", hxPost := "/hx/subscribe_feed", hxExt := "json-enc",
            hxVals := subscribeVars)("Subscribe"), hxSwap := "none"),
        source.description.map(d => div(cls := "feed-preview-desc", d)).getOrElse[Frag](""),
        div(cls := "feed-preview-url",
          div("Feed URL: "),
          a(href := source.xmlUrl, target := "_blank")(source.xmlUrl),
        ),
        source.htmlUrl.map(u => div(cls := "feed-preview-url", div("Website URL: "),
          a(href := u, target := "_blank")(u))).getOrElse[Frag](""),
        tag("progress")(id := s"feed-article-indicator-${source.id}", cls := "htmx-indicator"),
        div(cls := "feed-preview-article-list-loader", hxTrigger := "load once", hxSwap := "outerHTML",
          hxIndicator := s"#feed-article-indicator-${source.id}",
          hxGet := s"/hx/sources/${source.id}/articles/by_time/horizontal?noWrap")
      )
    }.ifEmpty(fs2.Stream.emit(emptyDom))

    val prefix = fs2.Stream.emits(Seq(header, raw("<div id='preview-feeds'>")))
    val suffix = fs2.Stream.emit(raw("</div>"))
    prefix ++ dom ++ suffix
  }

}
