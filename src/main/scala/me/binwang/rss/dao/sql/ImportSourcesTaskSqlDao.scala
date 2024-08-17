package me.binwang.rss.dao.sql

import cats.effect.IO
import doobie._
import doobie.implicits._
import io.getquill.Ord
import me.binwang.rss.dao.ImportSourcesTaskDao
import me.binwang.rss.model.ID.ID
import me.binwang.rss.model._

import java.time.ZonedDateTime

private[sql] class ImportSourcesTaskTable(implicit val connectionPool: ConnectionPool) extends BaseSqlDao {

  override def table: ID = "ImportSourcesTask"

  override def createTable(): IO[Unit] = {
    Fragment.const(s"""create table if not exists $table (
        id char($UUID_LENGTH) primary key,
        userID char($UUID_LENGTH),
        createdAt timestamp not null,
        totalSources int not null,
        failedSources int not null,
        successfulSources int not null)
        """)
      .update
      .run
      // one user can only have one import task at the same time
      .flatMap(_ => createIndex("userID", unique = true))
      .transact(xa)
      .map(_ => ())
  }
}


private[sql] class ImportSourcesMappingTable(implicit val connectionPool: ConnectionPool) extends BaseSqlDao {

  override def table: ID = "ImportSourcesTaskMapping"

  override def createTable(): IO[Unit] = {
    Fragment.const(
      s"""create table if not exists $table (
          taskID char($UUID_LENGTH),
          sourceID char(${ID.maxLength}) not null,
          xmlUrl varchar not null unique,
          error varchar default null,
          primary key (taskID, sourceID)
          )
         """)
      .update
      .run
      .flatMap(_ => createIndexWithFields(Seq(("taskID", false), ("error", false))))
      .transact(xa)
      .map(_ => ())
  }
}


class ImportSourcesTaskSqlDao(implicit val connectionPool: ConnectionPool)
    extends ImportSourcesTaskDao with BaseSqlDao {

  import dbCtx._

  private val importSourcesTaskTable = new ImportSourcesTaskTable()
  private val importSourcesMappingTable = new ImportSourcesMappingTable()

  override def table: ID = throw new NotImplementedError("Method table not implemented")

  override def createTable(): IO[Unit] = {
    importSourcesTaskTable.createTable() >> importSourcesMappingTable.createTable()
  }

  override def dropTable(): IO[Unit] = {
    importSourcesTaskTable.dropTable() >> importSourcesMappingTable.dropTable()
  }

  override def deleteAll(): IO[Unit] = {
    importSourcesTaskTable.deleteAll() >> importSourcesMappingTable.deleteAll()
  }

  override def insert(task: ImportSourcesTask, sourceMappings: Seq[ImportSourcesTaskMapping]): IO[Unit] = {
    (for {
      _ <- run(quote {query[ImportSourcesTask].insertValue(lift(task))})
      _ <- run(quote {liftQuery(sourceMappings).foreach(s => query[ImportSourcesTaskMapping].insertValue(s))})
    } yield ()).transact(xa)
  }

  override def deleteByUser(userID: ID): IO[Unit] = {
    (for {
      taskID <- run(query[ImportSourcesTask]
        .filter(_.userID == lift(userID))
        .take(1)
        .map(_.id)).map(_.head)
      _ <- run(quote { query[ImportSourcesTask].filter(_.id == lift(taskID)).delete })
      _ <- run(quote { query[ImportSourcesTaskMapping].filter(_.taskID == lift(taskID)).delete })
    } yield ()).transact(xa).handleError{case _: NoSuchElementException => ()}
  }

  override def getByUserWithUpdatedStats(userID: ID, now: ZonedDateTime): IO[Option[ImportSourcesTask]] = {
    val taskRun = for {
      task <- run(quote {
        query[ImportSourcesTask]
          .filter(_.userID == lift(userID))
          .sortBy(_.createdAt)(Ord.desc)
          .take(1)
      }).map(_.head)
      taskID = task.id
      successCount <- run(quote {
        query[ImportSourcesTaskMapping]
          .filter(_.taskID == lift(taskID))
          .filter(_.error.isEmpty)
          .join(query[Source]).on(_.sourceID == _.id)
          .map{case (_, source) => source}
          .filter(_.fetchErrorCount == 0)
          .filter(_.fetchCompletedAt.exists(_ > lift(now.minusHours(2)))) // if it's fetched in the past 2 hours
          .size
      }).map(_.toInt)
      failedCount <- run(quote {
        query[ImportSourcesTaskMapping]
          .filter(_.taskID == lift(taskID))
          .leftJoin(query[Source]).on(_.sourceID == _.id)
          .filter{case (mapping, source) => mapping.error.isDefined ||
            (source.exists(_.fetchFailedMsg.isDefined) &&
              source.exists(_.fetchStatus == lift(FetchStatus.SCHEDULED)))}
          .size
      }).map(_.toInt)
      _ <- run(quote {
        query[ImportSourcesTask].update(
          _.failedSources -> lift(failedCount),
          _.successfulSources -> lift(successCount),
        )
      })
      updatedTask = task.copy(
        failedSources = failedCount,
        successfulSources = successCount,
      )
    } yield updatedTask

    taskRun.transact(xa).map(Some(_)).handleError{case _: NoSuchElementException => None}
  }

  override def updateFailedMessage(taskID: ID, sourceIDs: Seq[ID], msg: String): IO[Boolean] = {
    val q = quote {
      query[ImportSourcesTaskMapping]
        .filter(_.taskID == lift(taskID))
        .filter(s => liftQuery(sourceIDs).contains(s.sourceID))
        .update(_.error -> lift(Option(msg)))
    }
    run(q).transact(xa).map(_ > 0)
  }

  override def getFailedSources(taskID: ID): fs2.Stream[IO, ImportFailedSource] = {
    stream(quote {
      query[ImportSourcesTaskMapping]
        .filter(_.taskID == lift(taskID))
        .leftJoin(query[Source]).on(_.sourceID == _.id)
        .filter{case (mapping, source) => mapping.error.isDefined || source.exists(_.fetchFailedMsg.isDefined)}
    }).map { case (mapping, source) =>
      ImportFailedSource(mapping.xmlUrl, mapping.error.getOrElse(source.get.fetchFailedMsg.get))
    }.transact(xa)
  }

}
