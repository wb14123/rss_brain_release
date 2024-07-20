package me.binwang.rss.dao.sql

import cats.effect.IO
import doobie._
import doobie.implicits._
import me.binwang.rss.dao.PasswordResetDao
import me.binwang.rss.model.PasswordReset

class PasswordResetSqlDao(implicit val connectionPool: ConnectionPool) extends PasswordResetDao with BaseSqlDao  {

  import dbCtx._

  override def table: String = "passwordReset"

  override def createTable(): IO[Unit] = {
    Fragment.const(s"""create table if not exists $table (
            token char($UUID_LENGTH) primary key,
            userID char($UUID_LENGTH) not null unique,
            expireTime timestamp not null
          )
         """)
      .update
      .run
      .flatMap(_ => createIndex("userID"))
      .transact(xa)
      .map(_ => ())
  }

  override def listByUser(userID: String): fs2.Stream[IO, PasswordReset] = {
    val q = quote {
      query[PasswordReset].filter(_.userID == lift(userID))
    }
    stream(q).transact(xa)
  }

  override def insert(obj: PasswordReset): IO[Boolean] = {
    run(quote{query[PasswordReset].insertValue(lift(obj))}).transact(xa).map(_ > 0)
  }

  override def getByToken(token: String): IO[Option[PasswordReset]] = {
    run(quote {
      query[PasswordReset].filter(_.token == lift(token)).take(1)
    }).transact(xa).map(_.headOption)
  }

  override def deleteByToken(token: String): IO[Boolean] = {
    run(quote {
      query[PasswordReset].filter(_.token == lift(token)).delete
    }).transact(xa).map(_ > 0)
  }


}
