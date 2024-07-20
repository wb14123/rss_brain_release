package me.binwang.rss.webview.basic

import org.apache.commons.text.StringEscapeUtils
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import scala.language.implicitConversions

class HtmlCleaner(html: String) {

  def validHtml: String = {
    val cleanHtml = Jsoup.clean(html, Safelist.basicWithImages().addTags("figcaption"))
    val doc = Jsoup.parse(cleanHtml)
    doc.select("a").attr("target", "_blank")
    doc.html()
  }

  def escapeHtml: String = {
    StringEscapeUtils.escapeHtml4(html)
  }

  def encodeUrl: String = {
    URLEncoder.encode(html, StandardCharsets.UTF_8)
  }

  def toHttps: String = {
    if (html.startsWith("http://")) html.replace("http://", "https://") else html
  }

}

object HtmlCleaner {
  implicit def str2cleaner(html: String): HtmlCleaner = new HtmlCleaner(html);
}
