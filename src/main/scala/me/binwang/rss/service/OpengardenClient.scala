package me.binwang.rss.service

import cats.effect.IO
import io.circe.generic.auto._
import io.circe.parser
import io.circe.{Decoder, Error}
import me.binwang.rss.fetch.crawler.UrlValidator
import me.binwang.rss.model._
import org.typelevel.log4cats.{Logger, LoggerFactory}
import org.typelevel.log4cats.slf4j.Slf4jFactory
import sttp.client4._
import sttp.model.Uri

import java.net.URI
import java.time.{Instant, ZoneId, ZonedDateTime}
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

/**
 * Client of the Opengarden comment and article APIs.
 *
 * The API URL of an article is published in its feed, and its origin may be an address that this server cannot reach.
 * In that case the host is replaced by `baseUrl`, and the path and query string of the URL are kept.
 *
 * @param baseUrl Base URL of the Opengarden public listener, for example "http://opengarden:9020". When empty, the
 *                URL from the feed is used as it is.
 * @param articleReadTimeout Time limit of an article read. Empty sets no timeout on the request.
 */
private[service] class OpengardenClient(
    private val sttpBackend: Backend[IO],
    private val baseUrl: Option[String],
    private val urlValidator: UrlValidator,
    private val articleReadTimeout: Option[FiniteDuration] = None,
) {

  private implicit val loggerFactory: LoggerFactory[IO] = Slf4jFactory.create[IO]
  private implicit val logger: Logger[IO] = LoggerFactory.getLoggerFromClass[IO](this.getClass)

  private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
    "Chrome/125.0.0.0 Safari/537.36"

  // The API publishes comment times as Unix epoch seconds.
  private implicit val epochSecondsDecoder: Decoder[ZonedDateTime] = Decoder.decodeLong.emap { seconds =>
    Try(ZonedDateTime.ofInstant(Instant.ofEpochSecond(seconds), ZoneId.systemDefault()))
      .toEither.left.map(_ => s"Invalid unix timestamp for comment time: $seconds")
  }

  /**
   * Comments of the article that owns `commentUrl`.
   */
  def getComments(commentUrl: String): IO[ArticleComments] = {
    for {
      target <- IO.fromEither(parseTarget(commentUrl, CommentSourceNotSupported(commentUrl)))
      _ <- urlValidator.validate(target.toString)
      body <- fetch(target, None, "comments", CommentFetchFailed(_))
      comments <- decode(target, body)
    } yield comments
  }

  /**
   * HTML of the article body that `fullContentUrl` serves.
   */
  def getArticleBody(fullContentUrl: String): IO[String] = {
    val unsupported = ArticleFullContentFetchFailed(s"Unsupported article url: $fullContentUrl")
    for {
      target <- IO.fromEither(parseTarget(fullContentUrl, unsupported))
      _ <- urlValidator.validate(target.toString)
      body <- fetch(target, articleReadTimeout, "the article body", ArticleFullContentFetchFailed(_))
    } yield body
  }

  private def parseTarget(url: String, unsupported: Throwable): Either[Throwable, Uri] = {
    for {
      parsed <- parseUrl(url).toRight(unsupported)
      target <- Uri.parse(rewriteUrl(parsed)).left.map(_ => unsupported)
    } yield target
  }

  private def parseUrl(url: String): Option[URI] = {
    Try(URI.create(url)).toOption.filter { uri =>
      uri.isAbsolute && uri.getHost != null &&
        Option(uri.getScheme).exists(scheme => scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
    }
  }

  /*
   The query string is already URL encoded by the feed, so its raw form is copied into the request URL instead of being
   rebuilt from its parameters.
   */
  private def rewriteUrl(url: URI): String = {
    baseUrl.filter(_.nonEmpty) match {
      case Some(base) =>
        val query = Option(url.getRawQuery).map("?" + _).getOrElse("")
        base.stripSuffix("/") + url.getRawPath + query
      case None => url.toString
    }
  }

  private def fetch(target: Uri, timeout: Option[FiniteDuration], resource: String,
      toError: String => Throwable): IO[String] = {
    val request = basicRequest
      .get(target)
      .header("User-Agent", userAgent)
      .followRedirects(false)
      .response(asStringAlways)
    val requestWithTimeout = timeout match {
      case Some(value) => request.readTimeout(value)
      case None => request
    }
    for {
      response <- requestWithTimeout.send(sttpBackend)
      body <- if (response.code.isSuccess) {
        IO.pure(response.body)
      } else {
        val error = toError(errorMessage(response.body, response.statusText))
        logger.error(error)(s"Error status ${response.code.code} when fetching $resource from $target") >>
          IO.raiseError(error)
      }
    } yield body
  }

  private def decode(url: Uri, body: String): IO[ArticleComments] = {
    IO.fromEither(parser.decode[ArticleComments](body))
      .adaptError {
        case err: Error =>
          CommentFetchFailed(errorMessage(body, "Failed to parse the comment response"), Some(err))
      }
      .onError(err => logger.error(err)(s"Failed to parse comments fetched from $url"))
  }

  private def errorMessage(body: String, statusText: String): String = {
    parser.parse(body).toOption
      .flatMap(_.hcursor.get[String]("error").toOption)
      .filter(_.nonEmpty)
      .getOrElse(statusText)
  }

}
