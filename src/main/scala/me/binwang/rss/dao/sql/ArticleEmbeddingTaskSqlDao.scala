package me.binwang.rss.dao.sql
import cats.effect.IO
import cats.implicits._
import doobie._
import doobie.implicits._
import io.getquill.Ord
import me.binwang.rss.dao.ArticleEmbeddingTaskDao
import me.binwang.rss.model.{ArticleEmbeddingTask, ArticleEmbeddingTaskUpdater, EmbeddingUpdateStatus, ID}
import me.binwang.rss.model.ID.ID

import java.time.ZonedDateTime

class ArticleEmbeddingTaskSqlDao(implicit val connectionPool: ConnectionPool) extends ArticleEmbeddingTaskDao with BaseSqlDao {

  import dbCtx._

  override def table: String = "articleEmbeddingTask"

  override def createTable(): IO[Unit] = {
    Fragment
      .const(s"""
      create table if not exists $table (
      articleID char(${ID.maxLength}) primary key,
      title varchar not null,
      scheduledAt timestamp not null,
      status varchar not null,
      startedAt timestamp default null,
      finishedAt timestamp default null,
      retried int default 0
    )""")
      .update
      .run
      .flatMap(_ => createIndexWithFields(Seq(("status", false), ("scheduledAt", true))))
      .flatMap(_ => createIndexWithFields(Seq(("status", false), ("startedAt", true))))
      .flatMap(_ => createIndexWithFields(Seq(("status", false), ("finishedAt", true))))
      .map(_ => ())
      .transact(xa)
  }

  override def get(articleID: ID): IO[Option[ArticleEmbeddingTask]] = {
    run(quote {
      query[ArticleEmbeddingTask]
        .filter(_.articleID == lift(articleID))
        .take(1)
    }).transact(xa).map(_.headOption)
  }

  override def getTasksForUpdate(now: ZonedDateTime, size: Int): fs2.Stream[IO, ArticleEmbeddingTask] = {
    val taskQuery = quote {
      query[ArticleEmbeddingTask]
        .filter(task => task.scheduledAt < lift(now))
        .filter(_.status == lift(EmbeddingUpdateStatus.PENDING))
        .sortBy(_.scheduledAt)(Ord.asc)
        .take(lift(size))
        .forUpdate()
    }

    val q = for {
      tasks <- run(taskQuery)
      _ <- run(
        query[ArticleEmbeddingTask]
          .filter(s => liftQuery(tasks.map(_.articleID)).contains(s.articleID))
          .update(
            _.status -> lift(EmbeddingUpdateStatus.UPDATING),
            _.startedAt -> lift(Option(now)),
          )
      )
    } yield tasks
    val result = q.transact(xa)
    fs2.Stream.evalSeq(result)
  }

  override def schedule(task: ArticleEmbeddingTask): IO[Unit] = {
    run( quote {
      query[ArticleEmbeddingTask]
        .insertValue(lift(task))
        .onConflictIgnore
    }).
    // only update when title matches
    flatMap(result => if (result <= 0) {
      run(quote { query[ArticleEmbeddingTask]
        .filter(_.articleID == lift(task.articleID))
        .filter(_.title != lift(task.title))
        .update(
          _.title -> lift(task.title),
          _.status -> lift(EmbeddingUpdateStatus.PENDING),
          _.scheduledAt -> lift(task.scheduledAt),
          _.startedAt -> lift(Option.empty[ZonedDateTime]),
          _.finishedAt -> lift(Option.empty[ZonedDateTime]),
          _.retried -> lift(0),
        )
      })
    } else result.pure[ConnectionIO])
    .transact(xa).map(_ => ())
  }

  override def update(articleID: ID, updater: ArticleEmbeddingTaskUpdater): IO[Boolean] = {
    val q = dynamicQuery[ArticleEmbeddingTask]
      .filter(_.articleID == lift(articleID))
      .update(
        setOpt(_.scheduledAt, updater.scheduledAt),
        setOpt(_.status, updater.status),
        setOpt(_.startedAt, updater.startedAt),
        setOpt(_.finishedAt, updater.finishedAt),
        setOpt(_.retried, updater.retried),
      )
    run(q).transact(xa).map(_ > 0)
  }

  override def deleteFinishedTasks(finishedBefore: ZonedDateTime): IO[Int] = {
    val q = quote {
      query[ArticleEmbeddingTask]
        .filter(_.status == lift(EmbeddingUpdateStatus.FINISHED))
        .filter(_.finishedAt.exists(_ < lift(finishedBefore)))
        .delete
    }
    run(q).transact(xa).map(_.toInt)
  }

  override def rescheduleTasks(startedBefore: ZonedDateTime, now: ZonedDateTime): IO[Int] = {
    val q = quote {
      query[ArticleEmbeddingTask]
        .filter(_.status == lift(EmbeddingUpdateStatus.UPDATING))
        .filter(_.startedAt.exists(_ < lift(startedBefore)))
        .update(
          _.status -> lift(EmbeddingUpdateStatus.PENDING),
          _.scheduledAt -> lift(now),
          _.startedAt -> lift(Option.empty[ZonedDateTime]),
          _.finishedAt -> lift(Option.empty[ZonedDateTime]),
          t => t.retried -> (t.retried + 1),
        )
    }
    run(q).transact(xa).map(_.toInt)
  }
}
