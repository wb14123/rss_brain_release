package me.binwang.rss.webview.widgets

import me.binwang.rss.webview.basic.ScalaTagAttributes.xInit
import scalatags.Text.all._

object SourceIcon {
  def apply(sourceID: String): Frag = img (
    cls := "source-avatar",
    attr("loading") := "lazy",
    attr("x-bind:src") := s"getInitSourceImage('$sourceID')",
    xInit := s"addSourceIconErrorHandler($$el, '$sourceID');"
  )
}
