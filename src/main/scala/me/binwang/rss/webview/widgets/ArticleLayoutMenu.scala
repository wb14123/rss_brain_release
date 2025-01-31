package me.binwang.rss.webview.widgets

import me.binwang.rss.model.ArticleListLayout
import me.binwang.rss.model.ArticleListLayout.ArticleListLayout
import me.binwang.rss.webview.basic.ScalaTagAttributes._
import scalatags.Text.all._

object ArticleLayoutMenu {

  private def menuItem(updateLink: String, articleListLayout: ArticleListLayout, icon: String, name: String): Frag = {
    a(nullHref, hxPost := updateLink, hxVals := s"""{"articleListLayout": "${articleListLayout.toString}"}""",
      hxExt := "json-enc", TextWithIcon(icon, name))
  }

  def apply(updateLink: String): Frag = {
    popoverMenu(
      PopoverMenu.subMenuAttrs,
      a(nullHref, TextWithIcon("dashboard", "Layout")),
      popoverContent(
        zIndex := "11",
        cls := "folder-select-menu",
        menuItem(updateLink, ArticleListLayout.LIST, "view_agenda", "List"),
        menuItem(updateLink, ArticleListLayout.GRID, "grid_view", "Grid"),
        menuItem(updateLink, ArticleListLayout.SOCIAL_MEDIA, "view_day", "Social Media"),
        menuItem(updateLink, ArticleListLayout.COMPACT, "view_compact", "Compact"),
      ),
    )
  }

}
