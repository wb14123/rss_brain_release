package me.binwang.rss.webview.widgets

import scalatags.Text.all._

object LogoHeader {
  def apply(): Frag = div(
      cls := "login-title",
      img(src := "/static/assets/icon_outlined.png"),
      div(id := "login-title-text")("RSS Brain"),
    )
}
