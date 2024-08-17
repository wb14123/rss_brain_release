package me.binwang.rss.dao

import cats.effect.IO
import me.binwang.rss.model.ID.ID
import me.binwang.rss.model.{ImportFailedSource, ImportSourcesTask, ImportSourcesTaskMapping}

import java.time.ZonedDateTime

trait ImportSourcesTaskDao {

  def insert(task: ImportSourcesTask, sourceMappings: Seq[ImportSourcesTaskMapping]): IO[Unit]
  def deleteByUser(userID: String): IO[Unit]
  def getByUserWithUpdatedStats(userID: String, now: ZonedDateTime): IO[Option[ImportSourcesTask]]
  def updateFailedMessage(taskID: String, sourceIDs: Seq[ID], msg: String): IO[Boolean]
  def getFailedSources(taskID: String): fs2.Stream[IO, ImportFailedSource]

}
