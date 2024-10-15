package me.binwang.rss.webview.basic

import cats.effect.IO
import me.binwang.rss.webview.basic.ScalaTagAttributes._
import org.http4s.{MediaType, Request, Response}
import org.http4s.dsl.io._
import org.http4s.headers._
import org.http4s.scalatags.ScalatagsInstances
import org.typelevel.ci.CIString
import scalatags.Text.all._
import scalatags.generic
import scalatags.text.Builder

object ContentRender extends ScalatagsInstances {

  val hxSwapContentAttrs: Seq[generic.AttrPair[Builder, String]] = Seq(
    hxTarget := "#content",
    hxSwap := "innerHTML show:window:top",
    hxIndicator := "#content-indicator",
    hxSync := "#content:replace",
  )

  def apply(contentUrl: String): Frag = {
    val showMenuScript = "window.innerWidth > 1280"
    val smallScreenScript = "window.innerWidth <= 1280"
    Html(
      div(id := "main",
        xData := s"{showMenu: $showMenuScript, smallScreen: $smallScreenScript, nsfwClass: loadNSFWClass()}",
        xOn("resize.window") := s"showMenu = $showMenuScript ; smallScreen = $smallScreenScript ; ",
        div(id := "folder-list", hxGet := "/hx/folders", hxTrigger := "load once", hxTarget := "this",
          hxSwap := "outerHTML"),
        div(id := "content-area",
          xBind("class") := "nsfwClass",
          tag("progress")(id := "content-indicator", cls := "htmx-indicator"),
          div(id := "content", hxHistoryElt,
            div(id := "load-content", hxGet := contentUrl, hxTrigger := "load once",
              hxIndicator := "#content-indicator", hxSwap := "outerHTML")
          )
        )
      )
    )
  }

  def wrapContentRaw(req: Request[IO])(content: => IO[Response[IO]]): IO[Response[IO]] = {
    if (req.headers.get(CIString("HX-Request")).isEmpty ||
        req.headers.get(CIString("X-History-Restore-Request")).isDefined) {
      Ok(apply(req.uri.toString()), `Content-Type`(MediaType.text.html))
    } else {
      content
    }
  }

  def wrapContent(req: Request[IO])(content: => fs2.Stream[IO, Frag]): IO[Response[IO]] = {
    if (req.headers.get(CIString("HX-Request")).isEmpty ||
        req.headers.get(CIString("X-History-Restore-Request")).isDefined) {
      Ok(apply(req.uri.toString()), `Content-Type`(MediaType.text.html))
    } else {
      Ok(content, `Content-Type`(MediaType.text.html))
    }
  }

  def wrapUrl(req: Request[IO])(urlIO: => IO[String]): IO[Response[IO]] = {
    if (req.headers.get(CIString("HX-Request")).isEmpty ||
        req.headers.get(CIString("X-History-Restore-Request")).isDefined) {
      urlIO.flatMap(url => Ok(apply(url), `Content-Type`(MediaType.text.html)))
    } else {
      // this means do not let hx take full redirect, just do a full redirect on this sub-page
      urlIO.flatMap(url => HttpResponse.fullRedirect("", url))
    }
  }

}
