package me.binwang.rss.webview.widgets

import me.binwang.rss.webview.basic.ScalaTagAttributes._
import scalatags.Text.all._
import scalatags.generic
import scalatags.text.Builder

object PopoverMenu {

  def menuAttrs: Seq[generic.AttrPair[Builder, String]] = Seq(
    cls := "folder-edit",
    xRef := "folderEditMenu",
    buttonSelector := "a",
    xBind("placement") := "smallScreen ? 'top' : 'left'",
    openEventClick, closeEvent := "click-button click-other",
    flip, shift,
    contentDisplay := "flex",
  )

  def subMenuAttrs: Seq[generic.AttrPair[Builder, String]] = Seq(
    cls := "folder-move-menu",
    buttonSelector := "a",
    xBind("placement") := "smallScreen ? 'right-end' : 'left-start'",
    flip, shift,
    openEventMouseEnter,
    closeEvent := "mouseleave click-content",
    contentDisplay := "flex",
  )

}
