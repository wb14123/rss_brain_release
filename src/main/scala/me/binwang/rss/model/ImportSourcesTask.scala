package me.binwang.rss.model

import me.binwang.rss.model.ID.ID

import java.time.ZonedDateTime

case class ImportSourcesTask(
  id: String,
  userID: String,
  createdAt: ZonedDateTime,
  totalSources: Int,
  failedSources: Int = 0,
  successfulSources: Int = 0,
) {
  def isFinished: Boolean = {
    totalSources <= failedSources + successfulSources
  }

  def isTimedOut(now: ZonedDateTime): Boolean = {
    createdAt.plusMinutes(5).isBefore(now) // timed out after 5 minutes
  }
}

case class ImportSourcesTaskStat(
  failedSources: Int,
  successfulSources: Int,
)

case class ImportSourcesTaskMapping(
  taskID: String,
  sourceID: ID,
  xmlUrl: String,
  error: Option[String],
)

case class ImportFailedSource(
  xmlUrl: String,
  error: String,
)