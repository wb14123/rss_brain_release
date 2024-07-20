package me.binwang.rss.dao.sql

import cats.effect.IO
import doobie._
import doobie.implicits._
import io.getquill.Ord
import me.binwang.rss.dao.FolderDao
import me.binwang.rss.model.{Folder, FolderUpdater}

class FolderSqlDao(implicit val connectionPool: ConnectionPool) extends FolderDao with BaseSqlDao {

  import dbCtx._

  override def table: String = "folder"

  override def createTable(): IO[Unit] = {
    Fragment.const(s"""create table if not exists $table (
            id char($UUID_LENGTH) primary key,
            userID char($UUID_LENGTH),
            name varchar not null,
            description varchar default null,
            position bigint not null,
            count int not null default 0,
            isUserDefault boolean not null default false,
            searchEnabled boolean not null default false,
            searchTerm varchar default null,
            expanded boolean not null default true,
            articleOrder varchar not null,
            recommend bool not null default false,
            language varchar default null,
            articleListLayout varchar not null,
            unique(userID, name)
          )
         """)
      .update
      .run
      .flatMap(_ => createIndex("userID"))
      .flatMap(_ => createIndex("position"))
      .flatMap(_ => createIndex("recommend"))
      .flatMap(_ => createIndex("language"))
      .transact(xa)
      .map(_ => ())

  }

  override def listByUser(userID: String, size: Int, startPosition: Long): fs2.Stream[IO, Folder] = {
    val q = quote {
      query[Folder]
        .filter(g => g.userID == lift(userID) && g.position >= lift(startPosition))
        .sortBy(_.position)(Ord.asc)
        .take(lift(size))
    }
    stream(q).transact(xa)
  }

  override def listByIDs(folderIDs: Seq[String]): fs2.Stream[IO, Folder] = {
    val q = quote {
      query[Folder].filter(f => lift(folderIDs).contains(f.id))
    }
    stream(q).transact(xa)
  }

  override def listRecommended(lang: String, size: Int = 20, startPosition: Long = 0): fs2.Stream[IO, Folder] = {
    val q = quote {
      query[Folder]
        .filter(f => f.language.contains(lift(lang)) || f.language.isEmpty)
        .filter(_.recommend == true)
        .filter(_.position > lift(startPosition))
        .sortBy(_.position)(Ord.asc)
        .take(lift(size))
    }
    stream(q).transact(xa)
  }


  override def insertIfNotExist(folder: Folder): IO[Boolean] =  {
    val q = quote {
      query[Folder].insertValue(lift(folder)).onConflictIgnore
    }
    run(q).transact(xa).map(_ > 0)
  }

  override def update(folderID: String, updater: FolderUpdater): IO[Boolean] = {
    val q = dynamicQuery[Folder]
      .filter(_.id == lift(folderID))
      .update(
        setOpt(_.name, updater.name),
        setOpt(_.description, updater.description),
        setOpt(_.position, updater.position),
        setOpt(_.searchEnabled, updater.searchEnabled),
        setOpt(_.searchTerm, updater.searchTerm),
        setOpt(_.expanded, updater.expanded),
        setOpt(_.articleOrder, updater.articleOrder),
        setOpt(_.recommend, updater.recommend),
        setOpt(_.language, updater.language),
        setOpt(_.articleListLayout, updater.articleListLayout),
      )
    run(q).transact(xa).map(_ > 0)
  }

  override def delete(folderID: String): IO[Boolean] = {
    val q = quote {
      query[Folder].filter(_.id == lift(folderID)).delete
    }
    run(q).transact(xa).map(_ > 0)
  }

  override def getByUserAndName(userID: String, name: String): IO[Option[Folder]] = {
    val q = quote {
      query[Folder]
        .filter(_.userID == lift(userID))
        .filter(_.name == lift(name))
        .take(1)
    }
    stream(q).transact(xa).compile.last
  }

  override def getUserDefaultFolder(userID: String): IO[Option[Folder]] = {
    val q = quote {
      query[Folder]
        .filter(_.userID == lift(userID))
        .filter(_.isUserDefault == true)
        .take(1)
    }
    stream(q).transact(xa).compile.last
  }

  override def getByID(folderID: String): IO[Option[Folder]] =  {
    val q = quote {
      query[Folder]
        .filter(_.id == lift(folderID))
        .take(1)
    }
    stream(q).transact(xa).compile.last
  }

  override def cleanupPositionForUser(userID: String): IO[Int] = {
    (fr"""update """ ++ Fragment.const(table)  ++ fr"""set position = (subq.row_num - 1) * 1000 from (
          select id, row_number() over (order by position) as row_num from """ ++ Fragment.const(table) ++ fr"""where userID = $userID
        ) subq where """ ++ Fragment.const(table) ++ fr""".id = subq.id"""
    ).update.run.transact(xa)
  }

  override def deleteAllForUser(userID: String): IO[Long] = {
    run(quote {
      query[Folder].filter(_.userID == lift(userID)).delete
    }).transact(xa)
  }
}
