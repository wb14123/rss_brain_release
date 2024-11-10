package me.binwang.rss.webview.widgets


import me.binwang.rss.webview.basic.ScalaTagAttributes._
import scalatags.Text.all._

object TextWithIcon {

  def apply(icon: String, text: String): Frag = {
    span(cls := "text-with-icon", iconSpan(icon), text)
  }

}
