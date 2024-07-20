package me.binwang.rss.dao.sql

import cats.effect.IO
import doobie._
import doobie.implicits._
import me.binwang.rss.dao.MoreLikeThisMappingDao
import me.binwang.rss.model.MoreLikeThisMapping
import me.binwang.rss.model.MoreLikeThisType.MoreLikeThisType


class MoreLikeThisMappingSqlDao(implicit val connectionPool: ConnectionPool)
  extends MoreLikeThisMappingDao with BaseSqlDao{

  import dbCtx._

  override def table: String = "moreLikeThisMapping"

  override def createTable(): IO[Unit] = {

    Fragment.const(s"""create table if not exists $table (
            fromID varchar not null,
            fromType varchar not null,
            moreLikeThisID varchar not null,
            moreLikeThisType varchar not null,
            userID char($UUID_LENGTH) not null,
            position bigint not null,
            PRIMARY KEY (fromID, fromType, moreLikeThisID, moreLikeThisType, userID)
          )
         """)
      .update
      .run
      .flatMap(_ => createIndexWithFields(Seq(("fromID", false), ("fromType", false), ("userID", false), ("position", false))))
      .transact(xa)
      .map(_ => ())
  }

  override def insertIfNotExists(mapping: MoreLikeThisMapping): IO[Boolean] = {
    val q = quote {
      query[MoreLikeThisMapping]
        .insertValue(lift(mapping))
        .onConflictIgnore
    }
    run(q).transact(xa).map(_ > 0)
  }

  override def delete(mapping: MoreLikeThisMapping): IO[Boolean] = {
    val q = quote {
      query[MoreLikeThisMapping]
        .filter(m => m.fromID == lift(mapping.fromID) && m.fromType == lift(mapping.fromType)
          && m.moreLikeThisID == lift(mapping.moreLikeThisID)
          && m.moreLikeThisType == lift(mapping.moreLikeThisType))
        .delete
    }
    run(q).transact(xa).map(_ > 0)
  }

  override def listByFromID(fromID: String, fromType: MoreLikeThisType, userID: String, size: Int,
      startPosition: Long): fs2.Stream[IO, MoreLikeThisMapping] = {
    val q = quote {
      query[MoreLikeThisMapping]
        .filter(m => m.fromID == lift(fromID) && m.fromType == lift(fromType)
          && m.userID == lift(userID) && m.position > lift(startPosition))
        .sortBy(_.position)
        .take(lift(size))
    }
    stream(q).transact(xa)
  }

  override def updatePosition(mapping: MoreLikeThisMapping): IO[Boolean] = {
    val q = quote {
      query[MoreLikeThisMapping]
        .filter(m => m.fromID == lift(mapping.fromID) &&
            m.fromType == lift(mapping.fromType) &&
            m.moreLikeThisID == lift(mapping.moreLikeThisID) &&
            m.moreLikeThisType == lift(mapping.moreLikeThisType) &&
            m.userID == lift(mapping.userID))
        .update(_.position -> lift(mapping.position))
    }
    run(q).transact(xa).map(_ > 0)
  }

  override def cleanupPosition(fromID: String, fromType: MoreLikeThisType, userID: String): IO[Int] = {
    fr"""update ${Fragment.const(table)} set position = subq.row_num * 1000 from (
          select moreLikeThisID, moreLikeThisType, row_number() over (order by position) as row_num from ${Fragment.const(table)}
          where fromID = $fromID AND fromType = ${fromType.toString} AND userID = $userID
        ) subq where
          ${Fragment.const(table)}.fromID = $fromID AND
          ${Fragment.const(table)}.fromType = ${fromType.toString} AND
          ${Fragment.const(table)}.moreLikeThisID = subq.moreLikeThisID AND
          ${Fragment.const(table)}.moreLikeThisType = subq.moreLikeThisType AND
          ${Fragment.const(table)}.userID = $userID
          """.update.run.transact(xa)
  }

  override def deleteAllForUser(userID: String): IO[Long] = {
    run(quote {
      query[MoreLikeThisMapping].filter(_.userID == lift(userID)).delete
    }).transact(xa)
  }
}
