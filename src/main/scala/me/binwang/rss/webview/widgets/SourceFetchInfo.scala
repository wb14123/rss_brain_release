package me.binwang.rss.webview.widgets

import me.binwang.rss.model.Source
import scalatags.Text.all._

object SourceFetchInfo {
  def apply(source: Source): Frag = {
    small(cls := "form-block",
      source.fetchCompletedAt.map { fetchCompleteAt =>
        div(span("Last fetched at "), DateTimeNode(fetchCompleteAt))
      }.getOrElse(""),
      div(span("Next fetched scheduled at "),
        DateTimeNode(source.fetchScheduledAt)),
    )
  }

}
