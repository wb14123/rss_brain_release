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
        link(rel := "stylesheet", href := "/static/lib/pico.jade.min.css"),
        link(rel := "stylesheet", href := "/static/lib/toastify-1.12.0.min.css"),
        link(rel := "stylesheet", href := "/static/lib/google-fonts.css"),
        link(rel := "stylesheet", href := "/static/lib/lite-yt-embed-0.3.2.css"),
        link(rel := "stylesheet", href := "/static/lib/somment.css"),
        link(rel := "stylesheet", href := "/static/css/main.css"),
        link(rel := "shortcut icon", href := "/static/assets/icons/Icon-192.png"),
        script(src := "/static/js/boolean-checkbox.js"),
        script(src := "/static/lib/htmx-1.9.10.js"),
        script(src := "/static/lib/toastify-1.12.0.js"),
        script(src := "/static/js/error-handler.js"),
        script(src := "/static/lib/htmx-1.9.10-json-enc.js", defer),
        script(src := "/static/lib/floating-ui-core-1.6.1.js", defer),
        script(src := "/static/lib/floating-ui-dom-1.6.4.js", defer),
        script(src := "/static/js/popover-menu.js", defer),
        script(src := "/static/js/match-id.js", defer),
        script(src := "/static/js/set-theme.js", defer),
        script(src := "/static/lib/alpine.js", defer),
        script(src := "/static/lib/dompurify-3.1.6.js"),
        script(src := "/static/lib/imgs.js"),
        script(src := "/static/lib/somment_component.js", `type` := "module"),
        script(src := "/static/js/source-images.js", defer),
        script(src := "/static/js/register-service-worker.js", defer),
        meta(name := "htmx-config", content := htmxConfig),
      ),
      body(bodyContent)
    ).toString())
  }

}
