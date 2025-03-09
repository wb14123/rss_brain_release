package me.binwang.rss.dao.sql

import cats.effect.IO
import cats.implicits._
import doobie._
import doobie.implicits._
import me.binwang.rss.dao.ArticleUserMarkingDao
import me.binwang.rss.model.{Article, ArticleUserMarking, ArticleUserMarkingUpdater, ArticleWithUserMarking, ID}
import me.binwang.rss.model.ID.ID

class ArticleUserMarkingSqlDao (implicit val connectionPool: ConnectionPool) extends ArticleUserMarkingDao with BaseSqlDao {

  import dbCtx._

  override def table: String = "articleUserMarking"

  override def createTable(): IO[Unit] = {
    Fragment.const(s"""
      create table if not exists $table (
        articleID char(${ID.maxLength}) not null,
        userID char($UUID_LENGTH) not null,
        bookmarked boolean not null default false,
        read boolean not null default false,
        deleted boolean not null default false,
        readProgress int not null default 0,
        PRIMARY KEY (articleID, userID)
      )""")
      .update
      .run
      .flatMap(_ => createIndex("articleID"))
      .flatMap(_ => createIndex("userID"))
      .flatMap(_ => createIndexWithFields(Seq(("userID", false), ("deleted", false))))
      .map(_ => ())
      .transact(xa)
  }

  override def updateMarking(articleId: ID, userId: ID, updater: ArticleUserMarkingUpdater): IO[Boolean] = {
    val insert = quote {
      query[ArticleUserMarking]
        .insertValue(lift(ArticleUserMarking(
          articleID = articleId,
          userID = userId,
          bookmarked = updater.bookmarked.getOrElse(false),
          read = updater.read.getOrElse(false),
          deleted = updater.deleted.getOrElse(false),
          readProgress = updater.readProgress.getOrElse(0),
        ))).onConflictIgnore
    }

    val update = dynamicQuery[ArticleUserMarking]
      .filter(e => e.articleID == lift(articleId) && e.userID == lift(userId))
      .update(
        setOpt(_.bookmarked, updater.bookmarked),
        setOpt(_.read, updater.read),
        setOpt(_.deleted, updater.deleted),
        setOpt(_.readProgress, updater.readProgress),
      )

    run(insert).flatMap { result =>
      if (result > 0) {
        result.pure[ConnectionIO]
      } else {
        run(update)
      }
    }.transact(xa).map(_ > 0)
  }

  override def get(articleID: ID, userID: String): IO[ArticleUserMarking] = {
    stream(quote {
      query[ArticleUserMarking]
        .filter(e => e.articleID == lift(articleID) && e.userID == lift(userID))
        .take(1)
    }).transact(xa).compile.last
      .map(_.getOrElse(ArticleUserMarking(userID = userID, articleID = articleID)))
  }

  override def getByArticles(articleStream: fs2.Stream[IO, Article], userID: String
      ): fs2.Stream[IO, ArticleWithUserMarking] = {
    val result = articleStream.compile.toList.flatMap { articles =>
      val articleIDs = articles.map(_.id)
      stream(quote {
        query[ArticleUserMarking]
          .filter(e => e.userID == lift(userID))
          .filter(e => liftQuery(articleIDs).contains(e.articleID))
      }).transact(xa).map(m => (m.articleID, m)).compile.toList.map(_.toMap)
    }.map { markings =>
      articleStream.map(article => ArticleWithUserMarking(article,
        markings.getOrElse(article.id, ArticleUserMarking(article.id, userID))))
    }
    fs2.Stream.eval(result).flatten
  }

  override def deleteAllForUser(userId: String): IO[Long] = {
    run(quote {
      query[ArticleUserMarking].filter(_.userID == lift(userId)).delete
    }).transact(xa)
  }
}
