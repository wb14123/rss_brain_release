package me.binwang.rss.webview.widgets

import scalatags.Text.all.selected
import scalatags.generic.AttrPair
import scalatags.text.Builder

object TimeSelection {

  val daySeconds: String = (24 * 3600).toString
  val weekSeconds: String = (7 * 24 * 3600).toString
  val monthSeconds: String = (31 * 24 * 3600).toString
  val yearSeconds: String = (365 * 24 * 3600).toString

  def timeSelected(timeStr: String, param: Option[String]): Option[AttrPair[Builder, String]] =
    param.filter(_.equals(timeStr)).map(_ => selected)

}
