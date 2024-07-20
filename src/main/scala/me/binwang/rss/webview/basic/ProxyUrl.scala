package me.binwang.rss.webview.basic

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object ProxyUrl {

  def apply(url: String): String = {
    s"https://http-proxy.rssbrain.com/?link=${URLEncoder.encode(url, StandardCharsets.UTF_8)}"
  }

}
