package me.binwang.rss.webview.basic

import cats.effect.IO
import org.http4s.{EntityEncoder, Header, Headers, Request, Response, Status}
import org.typelevel.ci.CIString

object HttpResponse {

  def redirect(reason: String, redirectUrl: String, req: Request[IO]) = {
    if (req.headers.get(CIString("HX-Request")).isEmpty ||
        req.headers.get(CIString("X-History-Restore-Request")).isDefined) {
      fullRedirect(reason, redirectUrl)
    } else {
      hxRedirect(reason, redirectUrl)
    }
  }

  def hxRedirect(reason: String, redirectUrl: String) = {
    implicit val customEntityEncoder: EntityEncoder[IO, String] = EntityEncoder.stringEncoder[IO]
    IO(Response(
      Status.Ok,
      headers = Headers(
        Header.Raw(CIString("HX-Redirect"), redirectUrl),
      )
    ).withEntity(reason))
  }

  def hxRefresh = {
    implicit val customEntityEncoder: EntityEncoder[IO, String] = EntityEncoder.stringEncoder[IO]
    IO(Response(
      Status.Ok,
      headers = Headers(
        Header.Raw(CIString("HX-Redirect"), "true"),
      )
    ).withEntity(""))
  }

  def fullRedirect(reason: String, redirectUrl: String) = {
    implicit val customEntityEncoder: EntityEncoder[IO, String] = EntityEncoder.stringEncoder[IO]
    IO(Response(
      Status.Found,
      headers = Headers(
        Header.Raw(CIString("Location"), redirectUrl),
      )
    ).withEntity(reason))
  }

}
