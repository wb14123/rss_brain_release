package me.binwang.rss.dao

import cats.effect.IO
import me.binwang.rss.model.{User, UserUpdater}

trait UserDao {

  def getByID(userID: String): IO[Option[User]]
  def getByEmail(email: String): IO[Option[User]]
  def getByActiveCode(activeCode: String): IO[Option[User]]
  def insertIfNotExists(user: User): IO[Boolean]
  def update(userID: String, updater: UserUpdater): IO[Boolean]
  def delete(userID: String): IO[Boolean]

}
