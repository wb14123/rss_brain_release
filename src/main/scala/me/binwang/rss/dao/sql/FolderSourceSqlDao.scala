package me.binwang.rss.dao.sql
import cats.effect.IO
import doobie._
import doobie.implicits._
import io.getquill._
import me.binwang.rss.dao.FolderSourceDao
import me.binwang.rss.model.ID.ID
import me.binwang.rss.model.{FolderSource, FolderSourceMapping, FolderSourceMappingUpdater, ID, Source}

class FolderSourceSqlDao(implicit val connectionPool: ConnectionPool) extends FolderSourceDao with BaseSqlDao {
  import dbCtx._

  override def table: String = "folderSourceMapping"

  override def createTable(): IO[Unit] = {
    Fragment.const(s"""create table if not exists $table (
            folderID char($UUID_LENGTH) not null,
            sourceID char(${ID.maxLength}) not null,
            userID char($UUID_LENGTH) not null,
            position bigint not null,
            customSourceName varchar default null,
            showTitle bool not null default true,
            showFullArticle bool not null default false,
            showMedia bool not null default false,
            articleOrder varchar not null,
            articleListLayout varchar not null,
            PRIMARY KEY (folderID, sourceID)
          )
         """)
      .update
      .run
      .flatMap(_ => createIndex("folderID"))
      .flatMap(_ => createIndex("userID"))
      .flatMap(_ => createIndex("sourceID"))
      .flatMap(_ => createIndex("position"))
      .flatMap(_ => createIndexWithFields(Seq(("userID", false), ("position", false))))
      .transact(xa)
      .map(_ => ())
  }


  override def get(folderID: String, sourceID: ID): IO[Option[FolderSource]] = {
    val q = quote {
      query[FolderSourceMapping]
        .join(query[Source])
        .on(_.sourceID == _.id)
        .filter(_._1.folderID == lift(folderID))
        .filter(_._1.sourceID == lift(sourceID))
        .take(1)
    }
    run(q).transact(xa).map(_.headOption.map{case (mapping, source) => FolderSource(mapping, source)})
  }

  override def getSourcesByFolder(folderID: String, size: Int, startPosition: Long): fs2.Stream[IO, FolderSource] = {
    val q = quote {
      query[FolderSourceMapping]
        .join(query[Source])
        .on(_.sourceID == _.id)
        .filter(_._1.folderID == lift(folderID))
        .filter(_._1.position > lift(startPosition))
        .sortBy(_._1.position)(Ord.asc)
        .take(lift(size))
    }
    stream(q).transact(xa).map{case (mapping, source) => FolderSource(mapping, source)}
  }

  override def getSourcesByFolderReverse(folderID: String, size: Int, startPosition: Long): fs2.Stream[IO, FolderSource] = {
    val q = quote {
      query[FolderSourceMapping]
        .join(query[Source])
        .on(_.sourceID == _.id)
        .filter(_._1.folderID == lift(folderID))
        .filter(_._1.position < lift(startPosition))
        .sortBy(_._1.position)(Ord.desc)
        .take(lift(size))
    }
    stream(q).transact(xa).map{case (mapping, source) => FolderSource(mapping, source)}
  }

  override def getSourcesByUser(userID: String, size: Int, startPosition: Long): fs2.Stream[IO, FolderSource] = {
    val q = quote {
      query[FolderSourceMapping]
        .join(query[Source])
        .on(_.sourceID == _.id)
        .filter(_._1.userID == lift(userID))
        .filter(_._1.position >= lift(startPosition))
        .sortBy(_._1.position)(Ord.asc)
        .take(lift(size))
    }
    stream(q).transact(xa).map{case (mapping, source) => FolderSource(mapping, source)}
  }


  override def getSourcesByID(userID: String, sourceID: String, size: Int, skip: Int): fs2.Stream[IO, FolderSource] = {
    val q = quote {
      query[FolderSourceMapping]
        .join(query[Source])
        .on(_.sourceID == _.id)
        .filter(_._1.userID == lift(userID))
        .filter(_._1.sourceID == lift(sourceID))
        .sortBy(m => (m._1.folderID, m._1.position))(Ord.asc)
        .drop(lift(skip))
        .take(lift(size))
    }
    stream(q).transact(xa).map{case (mapping, source) => FolderSource(mapping, source)}
  }

  override def addSourceToFolder(folderSourceMapping: FolderSourceMapping): IO[Boolean] = {
    // TODO: add count in folder table if insert successful
    val q = quote {
      query[FolderSourceMapping]
        .insertValue(lift(folderSourceMapping))
        .onConflictIgnore
    }
    run(q).transact(xa).map(_ > 0)
  }

  override def delSourceFromFolder(folderID: String, sourceID: ID): IO[Boolean] = {
    // TODO: decrease count in folder table if delete successful
    val q = quote {
      query[FolderSourceMapping]
        .filter(mapping => mapping.folderID == lift(folderID) && mapping.sourceID == lift(sourceID))
        .delete
    }
    run(q).transact(xa).map(_ > 0)
  }

  override def updateSourceOrder(folderID: String, sourceID: ID, position: Long): IO[Boolean] = {
    val q = quote {
      query[FolderSourceMapping]
        .filter(mapping => mapping.folderID == lift(folderID) && mapping.sourceID == lift(sourceID))
        .update(_.position -> lift(position))
    }
    run(q).transact(xa).map(_ > 0)
  }

  override def delAllInFolder(folderID: String): IO[Unit] = {
    val q = quote {
      query[FolderSourceMapping]
        .filter(mapping => mapping.folderID == lift(folderID))
        .delete
    }
    run(q).transact(xa).map(_ => ())
  }

  override def delSourceForUser(userID: String, sourceID: ID): IO[Unit] = {
    val q = quote {
      query[FolderSourceMapping]
        .filter(mapping => mapping.userID == lift(userID) && mapping.sourceID == lift(sourceID))
        .delete
    }
    run(q).transact(xa).map(_ => ())
  }

  override def copySources(fromFolderID: String, toFolderID: String): IO[Int] = {
    val select = quote {
      query[FolderSourceMapping]
        .filter(mapping => mapping.folderID == lift(fromFolderID))
        .map(mapping => FolderSourceMapping(
          folderID = lift(toFolderID),
          sourceID = mapping.sourceID,
          userID = mapping.userID,
          position = mapping.position,
          customSourceName = mapping.customSourceName,
          showTitle = mapping.showTitle,
          showFullArticle = mapping.showFullArticle,
          showMedia = mapping.showMedia,
          articleOrder = mapping.articleOrder,
          articleListLayout = mapping.articleListLayout,
        ))
    }
    val q = quote(sql"insert into #$table $select".as[Insert[FolderSourceMapping]])
    run(q).transact(xa).map(_.toInt)
  }

  override def cleanupPositionInFolder(folderID: String): IO[Int] = {
    fr"""update ${Fragment.const(table)} set position = subq.row_num * 1000 from (
          select sourceID, row_number() over (order by position) as row_num from ${Fragment.const(table)} where folderID = $folderID
        ) subq where ${Fragment.const(table)}.sourceID = subq.sourceID AND ${Fragment.const(table)}.folderID = $folderID"""
      .update.run.transact(xa)
  }

  override def updateSourceInfo(userID: String, sourceID: ID, folderSourceMappingUpdater: FolderSourceMappingUpdater
      ): IO[Int] = {
    val q = dynamicQuery[FolderSourceMapping]
      .filter(_.userID == lift(userID))
      .filter(_.sourceID == lift(sourceID))
      .update(
        setOpt(_.customSourceName, folderSourceMappingUpdater.customSourceName),
        setOpt(_.showTitle, folderSourceMappingUpdater.showTitle),
        setOpt(_.showFullArticle, folderSourceMappingUpdater.showFullArticle),
        setOpt(_.showMedia, folderSourceMappingUpdater.showMedia),
        setOpt(_.articleOrder, folderSourceMappingUpdater.articleOrder),
        setOpt(_.articleListLayout, folderSourceMappingUpdater.articleListLayout),
      )
    run(q).transact(xa).map(_.toInt)
  }

  override def deleteAllForUser(userID: ID): IO[Long] = {
    run(quote {
      query[FolderSourceMapping].filter(_.userID == lift(userID)).delete
    }).transact(xa)
  }

  override def getSourcesByUserAndPattern(userID: ID, pattern: ID, size: Index): fs2.Stream[IO, FolderSource] = {
    val q = quote {
      query[FolderSourceMapping]
        .join(query[Source])
        .on(_.sourceID == _.id)
        .filter(_._1.userID == lift(userID))
        .filter(_._2.xmlUrl like lift(pattern))
        .take(lift(size))
    }
    stream(q).transact(xa).map { case (mapping, source) => FolderSource(mapping, source) }
  }
}
