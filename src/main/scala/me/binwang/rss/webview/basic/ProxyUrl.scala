package me.binwang.rss.webview.basic

import com.typesafe.config.ConfigFactory

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object ProxyUrl {

  private val imageProxyHost = ConfigFactory.load().getString("image-proxy.host")

  def apply(url: String): String = {
    if (imageProxyHost.isEmpty) {
      url
    } else {
      s"https://$imageProxyHost/?link=${URLEncoder.encode(url, StandardCharsets.UTF_8)}"
    }
  }

}
