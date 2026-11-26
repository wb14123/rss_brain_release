package me.binwang.rss.fetch.crawler

import cats.effect.{IO, Resource}
import me.binwang.rss.model.TooManyRedirectsError
import sttp.client4._
import sttp.client4.http4s.Http4sBackend
import sttp.model.StatusCode

import java.io.{ByteArrayInputStream, InputStream}
import java.net.URI
import scala.util.Try

object HttpCrawler {
  def apply(): Resource[IO, HttpCrawler] = {
    Http4sBackend.usingDefaultEmberClientBuilder[IO]().map(new HttpCrawler(_, UrlValidator.allowAll, 10))
  }
}

class HttpCrawler(
    backend: Backend[IO],
    urlValidator: UrlValidator,
    maxRedirects: Int,
    redditCookie: Option[String] = None,
) extends Crawler {

  private val redirectStatuses: Set[StatusCode] = Set(
    StatusCode.MovedPermanently,     // 301
    StatusCode.Found,                // 302
    StatusCode.SeeOther,             // 303
    StatusCode.TemporaryRedirect,    // 307
    StatusCode.PermanentRedirect,    // 308
  )

  private def isRedditHost(url: String): Boolean = {
    Try(URI.create(url).getHost).toOption.exists { host =>
      host != null && {
        val lower = host.toLowerCase
        lower == "reddit.com" || lower.endsWith(".reddit.com")
      }
    }
  }

  override protected def doFetch(requestUrl: String): IO[InputStream] = {
    urlValidator.validate(requestUrl) >> fetchWithRedirects(requestUrl, maxRedirects)
  }

  private def fetchWithRedirects(url: String, remainingRedirects: Int): IO[InputStream] = {
    val request = basicRequest
      .get(uri"$url")
      .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
      .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
      .header("Accept-Language", "en-US,en;q=0.9")
      .followRedirects(false)
      .response(asByteArray)

    val requestWithCookie = redditCookie match {
      case Some(cookie) if isRedditHost(url) => request.header("Cookie", cookie)
      case _ => request
    }

    requestWithCookie
      .send(backend)
      .flatMap { response =>
        (redirectStatuses.contains(response.code), response.header("Location")) match {
          case (true, Some(location)) =>
            if (remainingRedirects <= 0) {
              IO.raiseError(TooManyRedirectsError(url, maxRedirects))
            } else {
              val resolvedUrl = URI.create(url).resolve(location).toString
              urlValidator.validate(resolvedUrl) >> fetchWithRedirects(resolvedUrl, remainingRedirects - 1)
            }
          case _ =>
            response.body match {
              case Left(err) => IO.raiseError(new Exception(err))
              case Right(bytes) => IO.pure(new ByteArrayInputStream(bytes))
            }
        }
      }
  }
}
