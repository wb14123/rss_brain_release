package me.binwang.rss.webview.auth

import cats.effect.IO
import me.binwang.rss.model.UserNotAuthorized
import org.http4s.Request

import scala.language.implicitConversions

object CookieGetter {
  implicit def reqToCookieGetter(req: Request[IO]): CookieGetter = new CookieGetter(req)
}


class CookieGetter(req: Request[IO]) {
  def authToken: String = {
    req.cookies.find(_.name == "token") match {
      case None => throw UserNotAuthorized("")
      case Some(v) => v.content
    }
  }
}
