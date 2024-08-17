package me.binwang.rss.dao.sql

import cats.effect.IO
import cats.effect.kernel.Clock
import doobie.Fragment
import doobie.implicits._
import io.getquill.{idiom => _}
import me.binwang.rss.dao.UserSessionDao
import me.binwang.rss.model.UserSession

import java.time.{ZoneId, ZonedDateTime}

class UserSessionSqlDao(implicit val connectionPool: ConnectionPool) extends UserSessionDao with BaseSqlDao {

  import dbCtx._

  override def table: String = "user_session"

  private implicit val userSessionSchema: dbCtx.SchemaMeta[UserSession] = schemaMeta[UserSession]("user_session")

  override def createTable(): IO[Unit] = {

    Fragment.const(
      s"""create table if not exists "$table" (
            token char($UUID_LENGTH) primary key,
            userID char($UUID_LENGTH) not null,
            expireTime timestamp not null,
            isAdmin boolean not null default false,
            subscribeEndTime timestamp not null,
            subscribed boolean not null default false
          )
         """)
      .update
      .run
      .flatMap(_ => createIndex("token"))
      .flatMap(_ => createIndex("userID"))
      .transact(xa)
      .map(_ => ())
  }

  override def insert(userSession: UserSession): IO[Boolean] = {
    val q = quote {
      query[UserSession].insertValue(lift(userSession))
    }
    run(q).transact(xa).map(_ > 0)
  }

  override def get(token: String): IO[Option[UserSession]] = {
    val q = quote {
      query[UserSession]
        .filter(_.token == lift(token))
        .take(1)
    }
    // these two options are not in the same transaction, that's okay because we can handle the token is not deleted
    stream(q).compile.last.transact(xa).flatMap {
      case Some(session) =>
        Clock[IO].realTimeInstant.flatMap { nowInstant =>
          val now = ZonedDateTime.ofInstant(nowInstant, ZoneId.systemDefault())
          if (session.expireTime.isBefore(now)) {
            delete(token).map(_ => None)
          } else {
            IO.pure(Some(session))
          }
        }
      case result => IO.pure(result)
    }
  }

  override def delete(token: String): IO[Boolean] = {
    val q = quote {
      query[UserSession]
        .filter(_.token == lift(token))
        .delete
    }
    run(q).transact(xa).map(_ > 0)
  }

  override def deleteByUser(userID: String): IO[Long] = {
    val q = quote {
      query[UserSession]
        .filter(_.userID == lift(userID))
        .delete
    }
    run(q).transact(xa)
  }

  override def updateSubscription(userID: String, subscribeEndTime: ZonedDateTime, subscribed: Boolean): IO[Long] = {
    val q = quote {
      query[UserSession]
        .filter(_.userID == lift(userID))
        .update(
          _.subscribeEndTime -> lift(subscribeEndTime),
          _.subscribed -> lift(subscribed),
        )
    }
    run(q).transact(xa)
  }
}
