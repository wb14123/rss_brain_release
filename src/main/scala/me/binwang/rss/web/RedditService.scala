package me.binwang.rss.web

import cats.effect.IO
import cats.effect.kernel.Clock
import me.binwang.rss.dao.RedditSessionDao
import me.binwang.rss.model.RedditSession
import me.binwang.rss.reddit.RedditGateway
import org.http4s.HttpRoutes

import java.time.{ZoneId, ZonedDateTime}
import org.http4s.dsl.io._

class RedditService(val redditSessionDao: RedditSessionDao, val redditGateway: RedditGateway) {

  private def authCallback(code: String, state: String): IO[String] = {
    redditSessionDao.getByState(state).flatMap{
      case Some(redditSession) =>
        for {
          redditToken <- redditGateway.getAccessToken(code)
          redditUser <-  redditGateway.getMe(redditToken.accessToken)
          nowInstant <- Clock[IO].realTimeInstant
          now = ZonedDateTime.ofInstant(nowInstant, ZoneId.systemDefault())
          updateSession = RedditSession(
            userID = redditSession.userID,
            redditUserID = redditUser.id,
            redditUserName = Some(redditUser.name),
            state = redditSession.state,
            createdAt = redditSession.createdAt,
            accessAcceptedAt = Some(now),
            token = Some(redditToken.accessToken),
            refreshToken = Some(redditToken.refreshToken),
            scope = Some(redditToken.scope),
            expiresInSeconds = Some(redditToken.expiresIn),
          )
          result <- redditSessionDao.updateByState(state, updateSession)
            .map(_ => "Reddit login successful, please go back to the app")
        } yield result
      case None =>
        IO.pure("Reddit auth failed")
    }
  }

  object CodeParam extends QueryParamDecoderMatcher[String]("code")
  object StateParam extends QueryParamDecoderMatcher[String]("state")
  val route: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "reddit" / "login_redirect" :? CodeParam(code) +& StateParam(state) =>
      Ok(authCallback(code, state))
  }

}
