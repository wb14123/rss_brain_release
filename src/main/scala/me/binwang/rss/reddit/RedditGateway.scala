package me.binwang.rss.reddit

import cats.effect.{Clock, IO}
import com.typesafe.config.ConfigFactory
import io.circe.Decoder
import me.binwang.rss.dao.RedditSessionDao
import me.binwang.rss.model.RedditSession
import me.binwang.rss.reddit.RedditModels.{RedditToken, RedditUser, SubReddit, SubRedditResponse}
import sttp.client3._
import sttp.client3.circe.asJson
import sttp.client3.logging.slf4j.Slf4jLoggingBackend

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.{ZoneId, ZonedDateTime}

class RedditGateway(implicit val redditSessionDao: Option[RedditSessionDao], val backend: SttpBackend[IO, _]) {

  private val baseUrl = "https://oauth.reddit.com"
  private val config = ConfigFactory.load()
  private val redirectUrl = config.getString("reddit.redirectUrl")
  private val redditClientID = config.getString("reddit.clientID")
  private val redditClientSecret = config.getString("reddit.clientSecret")

  private def send[T: Decoder: IsOption](request: RequestT[Identity, Either[String, String], Any]): IO[T] = {
    request
      .header("User-Agent", "RssBrain/0.1 by Me")
      .response(asJson[T])
      .send(Slf4jLoggingBackend(backend, logResponseBody = true, logRequestBody = true, sensitiveHeaders = Set()))
      .map(_.body)
      .flatMap {
        case Left(err) => IO.raiseError(err)
        case Right(body) => IO.pure(body)
      }
  }

  // get and refresh token if needed
  private def get[T: Decoder: IsOption](curRedditSession: RedditSession, url: String, params: Map[String, Any] = Map()): IO[T] = {
    val paramStr = params.toSeq
      .map{case (k, v) => s"${URLEncoder.encode(k, StandardCharsets.UTF_8)}=${URLEncoder.encode(v.toString, StandardCharsets.UTF_8)}"}
      .mkString("&")
    refreshTokenIfNeeded(curRedditSession).flatMap { redditSession =>
      send[T](basicRequest
        .header("Authorization", "bearer " + redditSession.token)
        .get(uri"$url?$paramStr")
      )
    }
  }

  private def get[T: Decoder: IsOption](token: String, url: String): IO[T] = {
    send[T](basicRequest
      .header("Authorization", "bearer " + token)
      .get(uri"$url")
    )
  }

  private def refreshTokenIfNeeded(redditSession: RedditSession): IO[RedditSession] = {
    Clock[IO].realTimeInstant.flatMap { nowInstant =>
      val now = ZonedDateTime.ofInstant(nowInstant, ZoneId.systemDefault())
      if (redditSession.accessAcceptedAt.get.plusSeconds(redditSession.expiresInSeconds.get).isAfter(now)) {
        refreshAccessToken(redditSession.refreshToken.get).flatMap { newToken =>
          val newSession = redditSession.copy(
            accessAcceptedAt = Some(ZonedDateTime.now()),
            expiresInSeconds = Some(newToken.expiresIn),
            token = Some(newToken.accessToken),
          )
          // some APIs don't need to use reddit session. If it's needed but reddit session is not defined, throw error
          redditSessionDao.get
            .updateByRedditUserID(redditSession.userID, redditSession.redditUserID, newSession)
            .map { _ => newSession }
        }
      } else {
        IO.pure(redditSession)
      }
    }
  }

  def getAccessToken(code: String): IO[RedditToken] = {
    val url = "https://www.reddit.com/api/v1/access_token"
    val req = basicRequest
      .auth.basic(redditClientID, redditClientSecret)
      .post(uri"$url")
      .body(Map(
        "grant_type" -> "authorization_code",
        "code" -> code,
        "redirect_uri" -> redirectUrl,
      ))
    send[RedditToken](req)
  }

  def refreshAccessToken(refreshToken: String): IO[RedditToken] = {
    val url = "https://www.reddit.com/api/v1/access_token"
    val req = basicRequest
      .auth.basic(redditClientID, redditClientSecret)
      .post(uri"$url")
      .body(Map(
        "grant_type" -> "refresh_token",
        "refresh_token" -> refreshToken,
      ))
    send[RedditToken](req)
  }

  def getMe(token: String): IO[RedditUser] = {
    get[RedditUser](token, baseUrl + "/api/v1/me")
  }

  def getSubRedditInfo(subReddit: String): IO[SubReddit] = {
    val url = s"https://www.reddit.com/r/$subReddit/about.json"
    send[SubRedditResponse](basicRequest.get(uri"$url")).map(_.data)
  }

}
