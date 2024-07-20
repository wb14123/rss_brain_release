package me.binwang.rss.fetch.crawler

import cats.effect._

import java.io.InputStream

abstract class Crawler {

  def fetch(url: String): Resource[IO, InputStream] = {
    Resource.make {
      doFetch(url)
    } { inputStream =>
      IO(inputStream.close())
    }
  }

  protected def doFetch(url: String): IO[InputStream]
}
