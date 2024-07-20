package me.binwang.rss.dao.sql

import cats.effect.IO
import doobie._
import doobie.implicits._
import me.binwang.rss.dao.UserDeleteCodeDao
import me.binwang.rss.model.UserDeleteCode

class UserDeleteCodeSqlDao(implicit val connectionPool: ConnectionPool) extends UserDeleteCodeDao with BaseSqlDao {

  override def table: String = "userDeleteCode"

  import dbCtx._

  override def createTable(): IO[Unit] = {
    Fragment.const(
      s"""create table if not exists $table (
                code char($UUID_LENGTH) primary key,
                userID char($UUID_LENGTH) not null,
                expireDate timestamp not null
              )
             """)
      .update
      .run
      .transact(xa)
      .map(_ => ())
  }

  override def insert(userDeleteCode: UserDeleteCode): IO[Unit] = {
    run(quote {
      query[UserDeleteCode].insertValue(lift(userDeleteCode))
    }).transact(xa).map(_ => ())
  }

  override def getByCode(code: String): IO[Option[UserDeleteCode]] = {
    run(quote {
      query[UserDeleteCode].filter(_.code == lift(code)).take(1)
    }).transact(xa).map(_.headOption)
  }

  override def deleteByCode(code: String): IO[Boolean] = {
    run(quote {
      query[UserDeleteCode].filter(_.code == lift(code)).delete
    }).transact(xa).map(_ > 0)
  }


}
