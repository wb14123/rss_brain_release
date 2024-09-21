package me.binwang.rss.webview.basic


import scalatags.Text.all._
import scalatags.Text.tags2

object Html {

  def apply(bodyContent: Frag*): Frag = {

    val htmxConfig ="""{
        |"defaultSettleDelay": 0,
        |"refreshOnHistoryMiss":true,
        |"attributesToSettle":[],
        |"scrollBehavior":"auto"
        |}""".stripMargin

    raw("<!DOCTYPE html>" + html(
      head(
        meta(charset := "UTF-8"),
        meta(name := "viewport", content := "width=device-width, initial-scale=1, minimum-scale=1.0"),
        meta(name := "color-scheme", content := "light dark"),
        meta(httpEquiv := "Content-Security-Policy", content := "default-src 'self' 'unsafe-eval'; " +
          "img-src * data:; media-src *; style-src 'self' 'unsafe-inline'; font-src 'self' ; " +

          // for loading reddit comments
          "connect-src 'self' 'unsafe-eval' *.reddit.com http-proxy.rssbrain.com ; " +

          // for youtube embedded player
          "frame-src *.youtube-nocookie.com youtube-nocookie.com *.youtube.com youtube.com ; " +
          "script-src-elem 'self' 'unsafe-eval' https://www.youtube.com",
        ),
        tags2.title("RSS Brain - A Modern RSS Reader"),
        link(rel := "manifest", href := "/static/webmanifest.json"),
        link(rel := "stylesheet", href := "/static/dist/main.css"),
        link(rel := "shortcut icon", href := "/static/assets/icons/Icon-192.png"),
        script(src := "/static/dist/main.js", defer),
        meta(name := "htmx-config", content := htmxConfig),
      ),
      body(bodyContent)
    ).toString())
  }

}
