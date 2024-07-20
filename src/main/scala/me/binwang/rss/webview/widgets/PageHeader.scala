package me.binwang.rss.webview.widgets

import me.binwang.rss.webview.basic.ScalaTagAttributes._
import scalatags.Text.all._

object PageHeader {

  def apply(headerTitle: Option[String] = None): Frag = {
    div(
      id := "header",
      div(
        id := "header-menu-title",
        a(cls := "header-menu-button", xOnClick := "showMenu = true", xShow := "!showMenu", iconSpan("menu"),
          title := "Open Side Menu", nullHref),
        a(cls := "header-menu-button", xOnClick := "showMenu = false", xShow := "showMenu", iconSpan("arrow_back_ios"),
          title := "Close Side Menu", nullHref),
        if (headerTitle.isDefined) div(id := "header-title")(headerTitle.get) else "",
      ),
    )
  }

}
