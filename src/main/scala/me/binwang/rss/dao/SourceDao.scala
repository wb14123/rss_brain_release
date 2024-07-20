package me.binwang.rss.dao

import cats.effect.IO
import me.binwang.rss.model.ID.ID
import me.binwang.rss.model.{Source, SourceUpdater}

import java.time.ZonedDateTime

trait SourceDao {

  def insert(source: Source): IO[Unit]

  def update(id: ID, updater: SourceUpdater): IO[Boolean]

  def get(id: ID): IO[Option[Source]]

  /**
   * Get source urls need to be fetched. Which is source.fetchScheduledAt < now and source.fetchStatus = SCHEDULED.
   * The returned tasks will be sort by source.fetchScheduledAt.
   * It also set the returned source.fetchStatus to PENDING
   *
   * @param size How many sources to get
   * @param currentTime Get sources which scheduled time later than currentTime
   * @param pendingTimeoutMillis Get sources which state stuck in pending for pendingTimeoutMillis
   * @return The sources need to be fetched
   */
  def getFetchURLs(size: Int, currentTime: ZonedDateTime): fs2.Stream[IO, String]

  def timeoutFetching(timeBefore: ZonedDateTime, currentTime: ZonedDateTime): IO[Int]

  def pauseNotInFolderSources(): IO[Long]

  def pauseSourcesForDeactivatedUsers(now: ZonedDateTime): IO[Long]

  def resumeSourcesForActiveUsers(now: ZonedDateTime): IO[Long]

  def resumeSourcesForUser(userID: String): IO[Long]
}
