package me.binwang.rss.dao
import cats.effect.IO
import me.binwang.rss.model.ID.ID
import me.binwang.rss.model.{ArticleEmbeddingTask, ArticleEmbeddingTaskUpdater}

import java.time.ZonedDateTime

trait ArticleEmbeddingTaskDao {
  def createTable(): IO[Unit]
  def dropTable(): IO[Unit]
  def deleteAll(): IO[Unit]

  def get(articleID: ID): IO[Option[ArticleEmbeddingTask]]

  def getTasksForUpdate(scheduledBefore: ZonedDateTime, size: Int): fs2.Stream[IO, ArticleEmbeddingTask]

  /*
   Insert if there is no task for this articleID, otherwise update the existing task for fetching
   (only happens when title is different)
   */
  def schedule(task: ArticleEmbeddingTask): IO[Unit]

  def update(articleID: ID, updater: ArticleEmbeddingTaskUpdater): IO[Boolean]

  def deleteFinishedTasks(finishedBefore: ZonedDateTime): IO[Int]

  def rescheduleTasks(startedBefore: ZonedDateTime, now: ZonedDateTime): IO[Int]
}
