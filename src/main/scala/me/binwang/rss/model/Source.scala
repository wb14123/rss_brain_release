package me.binwang.rss.model

import me.binwang.rss.model.ArticleOrder.ArticleOrder
import me.binwang.rss.model.FetchStatus.FetchStatus
import me.binwang.rss.model.ID.ID

import java.time.ZonedDateTime
import scala.concurrent.duration.DurationInt

/**
 * Source fetching status.
 */
object FetchStatus extends Enumeration {
  type FetchStatus = Value

  /**
   * Scheduled but haven't started
   */
  val SCHEDULED: FetchStatus = Value

  /**
   * The system has pulled the record into the work queue, but the work hasn't been started yet
   */
  val PENDING: FetchStatus = Value

  /**
   * The fetch has started and is in progress. It will go to SCHEDULED state after finished.
   */
  val FETCHING: FetchStatus = Value

  /**
   * Source is paused for fetching because no paid user is subscribing it.
   */
  val PAUSED: FetchStatus = Value
}

object SourceID {
  def apply(xmlUrl: String): ID = ID.hash(xmlUrl)
}

/**
 * Source means something a user subscribed for, like a RSS or ATOM feed.
 *
 * @param id Source ID. Hashed from `xmlUrl` so it is unique.
 * @param xmlUrl The URL to get the source.
 * @param importedAt When this is imported to RSS Brain.
 * @param updatedAt When this is last updated.
 * @param title The title of the source. Parsed from feed.
 * @param htmlUrl The original site URL. Parsed from feed.
 * @param iconUrl The URL of the site icon. Parsed from feed.
 * @param showTitle Whether to show title in article list by default. (Need to migrated to [[ArticleListLayout]]).
 *                  User can override this in their [[FolderSourceMapping]].
 * @param showFullArticle Whether to show full article in article list by default. (Need to migrate to [[ArticleListLayout]]).
 *                        User can override this in their [[FolderSourceMapping]].
 * @param showMedia Whether to show medias in article list by default. (Need to migrate to [[ArticleListLayout]]).
 *                  User can override this in their [[FolderSourceMapping]].
 * @param fetchScheduledAt When is the next fetch scheduled.
 * @param fetchStartedAt When is the current fetch started.
 * @param fetchCompletedAt When is the last fetch completed.
 * @param fetchFailedMsg The error message if last fetch failed.
 * @param fetchDelayMillis The average time to wait for the next fetch. Can increase if there are multiple errors.
 * @param fetchErrorCount How many continues failed fetch occurred. Will be set to 0 once a successful fetch is finished.
 *
 * @see [[FolderSourceMapping]]
 * @see [[ArticleListLayout]]
 */
case class Source(
  id: ID,
  xmlUrl: String,

  // platform related data
  importedAt: ZonedDateTime,
  updatedAt: ZonedDateTime,

  // source metadata
  title: Option[String] = None,
  htmlUrl: Option[String] = None,
  iconUrl: Option[String] = None,

  // default display properties
  showTitle: Boolean = true,
  showFullArticle: Boolean = false,
  showMedia: Boolean = false, description: Option[String] = None,
  articleOrder: ArticleOrder = ArticleOrder.TIME,

  // fetch related data
  fetchScheduledAt: ZonedDateTime,
  fetchStartedAt: Option[ZonedDateTime] = None,
  fetchCompletedAt: Option[ZonedDateTime] = None,
  fetchStatus: FetchStatus = FetchStatus.SCHEDULED,
  fetchFailedMsg: Option[String] = None,
  fetchDelayMillis: Long = 1.hour.toMillis,
  fetchErrorCount: Int = 0,
) {
  // comment out during ID migration
  assert(id == SourceID(xmlUrl))
}


/**
 * Structure to update source. None means the field will not be updated.
 */
case class SourceUpdater(
  title: Option[Option[String]] = None,
  htmlUrl: Option[Option[String]] = None,
  description: Option[Option[String]] = None,

  fetchScheduledAt: Option[ZonedDateTime] = None,
  fetchStartedAt: Option[Option[ZonedDateTime]] = None,
  fetchCompletedAt: Option[Option[ZonedDateTime]] = None,
  fetchStatus: Option[FetchStatus] = None,
  fetchFailedMsg: Option[Option[String]] = None,
  fetchDelayMillis: Option[Long] = None,
  fetchErrorCount: Option[Int] = None,

  showTitle: Option[Boolean] = None,
  showFullArticle: Option[Boolean] = None,
  showMedia: Option[Boolean] = None,
  articleOrder: Option[ArticleOrder] = None,
  iconUrl: Option[Option[String]] = None,
)
