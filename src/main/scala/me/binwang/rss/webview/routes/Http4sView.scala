package me.binwang.rss.webview.routes

import cats.effect.IO
import org.http4s.HttpRoutes

trait Http4sView {
  val routes: HttpRoutes[IO]
}
