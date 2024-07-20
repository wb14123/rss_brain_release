package me.binwang.rss.dao.sql

import cats.effect.IO
import doobie._
import doobie.implicits._
import me.binwang.rss.dao.RedditSessionDao
import me.binwang.rss.model.RedditSession

class RedditSessionSqlDao(implicit val connectionPool: ConnectionPool) extends RedditSessionDao with BaseSqlDao {

  import dbCtx._

  override def table: String = "redditSession"

  override def createTable(): IO[Unit] = {
    Fragment.const(s"""create table if not exists $table (
            userID char($UUID_LENGTH),
            redditUserID varchar not null,
            redditUserName varchar default null,
            state char($UUID_LENGTH) not null,
            createdAt timestamp not null,
            accessAcceptedAt timestamp default null,
            token varchar default null,
            refreshToken varchar default null,
            scope varchar default null,
            expiresInSeconds int,
            PRIMARY KEY (userID, redditUserID)
          )
         """)
      .update
      .run
      .flatMap(_ => createIndex("userID"))
      .flatMap(_ => createIndex("redditUserID"))
      .flatMap(_ => createIndex("state"))
      .transact(xa)
      .map(_ => ())
  }

  override def insert(redditSession: RedditSession): IO[Boolean] = {
    val q = quote {
      query[RedditSession].insertValue(lift(redditSession))
    }
    run(q).transact(xa).map(_ > 0)
  }

  override def updateByState(state: String, redditSession: RedditSession): IO[Boolean] = {
    val q = quote {
      query[RedditSession].filter(_.state == lift(state)).updateValue(lift(redditSession))
    }
    run(q).transact(xa).map(_ > 0)
  }

  override def updateByRedditUserID(userID: String, redditUserID: String, redditSession: RedditSession): IO[Boolean] = {
    val q = quote {
      query[RedditSession]
        .filter(_.userID == lift(userID))
        .filter(_.redditUserID == lift(redditUserID))
        .updateValue(lift(redditSession))
    }
    run(q).transact(xa).map(_ > 0)
  }

  override def getByUserID(userID: String): fs2.Stream[IO, RedditSession] = {
    stream(quote {
      query[RedditSession].filter(_.userID == lift(userID)).filter(_.accessAcceptedAt.isDefined)
    }).transact(xa)
  }

  override def getByState(state: String): IO[Option[RedditSession]] = {
    run(quote {
      query[RedditSession].filter(_.state == lift(state)).take(1)
    })
      .transact(xa)
      .map(_.headOption)
  }

  override def getByRedditUserID(userID: String, redditUserID: String): IO[Option[RedditSession]] = {
    run(quote {
      query[RedditSession]
        .filter(_.userID == lift(userID))
        .filter(_.redditUserID == lift(redditUserID))
        .take(1)
    })
      .transact(xa)
      .map(_.headOption)
  }

  override def deleteByUserID(userID: String): IO[Boolean] = {
    run(quote {
      query[RedditSession].filter(_.userID == lift(userID)).delete
    })
      .transact(xa)
      .map(_ > 0)
  }

}
