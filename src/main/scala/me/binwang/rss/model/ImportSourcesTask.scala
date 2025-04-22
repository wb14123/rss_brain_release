package me.binwang.rss.model

import me.binwang.rss.model.ID.ID

import java.time.ZonedDateTime

/**
 * The progress of source imports from OPML.
 *
 * @param id The ID of the task.
 * @param userID Which user this task belongs to.
 * @param createdAt When the task is created.
 * @param totalSources How many sources are in the import task.
 * @param failedSources How many sources are failed.
 * @param successfulSources How many sources are successful.
 */
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

/**
 * The details of failed to import sources.
 *
 * @param xmlUrl The feed url.
 * @param error The error message.
 */
case class ImportFailedSource(
  xmlUrl: String,
  error: String,
)