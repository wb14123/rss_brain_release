package me.binwang.rss.dao

import cats.effect.IO
import me.binwang.rss.model.PasswordReset

trait PasswordResetDao {

  def createTable(): IO[Unit]
  def dropTable(): IO[Unit]
  def listByUser(userID: String): fs2.Stream[IO, PasswordReset]
  def insert(obj: PasswordReset): IO[Boolean]
  def getByToken(token: String): IO[Option[PasswordReset]]
  def deleteByToken(token: String): IO[Boolean]

}
