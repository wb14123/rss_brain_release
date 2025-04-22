package me.binwang.rss.webview.widgets

import me.binwang.rss.webview.basic.ScalaTagAttributes.xText
import scalatags.Text.all._

import java.time.ZonedDateTime

object DateTimeNode {

  def apply(time: ZonedDateTime): Frag = {
    val timestamp = time.toEpochSecond * 1000
    span(xText := s"new Date($timestamp).toLocaleString()")
  }

}
