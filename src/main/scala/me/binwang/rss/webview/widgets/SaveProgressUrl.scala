package me.binwang.rss.webview.widgets

import com.typesafe.config.ConfigFactory
import scalatags.Text.all.s

object SaveProgressUrl {

  private val websiteBaseUrl = ConfigFactory.load().getString("website.baseUrl")

  def apply(articleID: String): String = {
    s"$websiteBaseUrl/hx/articles/$articleID/state/read-progress/"
  }

}
