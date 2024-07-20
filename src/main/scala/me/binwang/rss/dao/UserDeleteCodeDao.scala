package me.binwang.rss.dao

import cats.effect.IO
import me.binwang.rss.model.UserDeleteCode

trait UserDeleteCodeDao {

  def createTable(): IO[Unit]
  def dropTable(): IO[Unit]
  def insert(userDeleteCode: UserDeleteCode): IO[Unit]
  def getByCode(code: String): IO[Option[UserDeleteCode]]
  def deleteByCode(code: String): IO[Boolean]

}
