package me.binwang.rss.fetch.crawler

import cats.effect.{IO, Resource}
import sttp.client3._
import sttp.client3.http4s.Http4sBackend

import java.io.{ByteArrayInputStream, InputStream}

// TODO: this is only needed in test, remove it and refactor tests
object HttpCrawler {
  def apply(): Resource[IO, HttpCrawler] = {
    Http4sBackend.usingDefaultEmberClientBuilder[IO]().map(new HttpCrawler(_))
  }
}

class HttpCrawler(backend: SttpBackend[IO, Any]) extends Crawler {
  override protected def doFetch(requestUrl: String): IO[InputStream] = {
    basicRequest
      .get(uri"$requestUrl")
      .followRedirects(true)
      .maxRedirects(10)
      .response(asByteArray)
      .send(backend)
      .flatMap(_.body match {
        case Left(err) => IO.raiseError(new Exception(err))
        case Right(bytes) => IO.pure(new ByteArrayInputStream(bytes))
      })
  }
}
