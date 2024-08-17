package me.binwang.rss.dao

import cats.effect.IO
import me.binwang.rss.model.UserSession

import java.time.ZonedDateTime

trait UserSessionDao {

  def insert(userSession: UserSession): IO[Boolean]
  def get(token: String): IO[Option[UserSession]]
  def delete(token: String): IO[Boolean]
  def deleteByUser(userID: String): IO[Long]
  def updateSubscription(userID: String, subscribeEndTime: ZonedDateTime, subscribed: Boolean): IO[Long]

}
