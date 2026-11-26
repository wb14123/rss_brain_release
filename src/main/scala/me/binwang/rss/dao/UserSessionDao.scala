package me.binwang.rss.dao

import cats.effect.IO
import me.binwang.rss.model.UserSession

import java.time.ZonedDateTime

trait UserSessionDao {

  def insert(userSession: UserSession): IO[Boolean]
  def get(token: String): IO[Option[UserSession]]
  def delete(token: String): IO[Boolean]
  def deleteByUser(userID: String, excludeApiTokens: Boolean = false): IO[Long]
  def deleteByUserAndTokenName(userID: String, tokenName: String): IO[Boolean]
  def listByUser(userID: String): fs2.Stream[IO, UserSession]
  def updateSubscription(userID: String, subscribeEndTime: ZonedDateTime, subscribed: Boolean): IO[Long]

}
