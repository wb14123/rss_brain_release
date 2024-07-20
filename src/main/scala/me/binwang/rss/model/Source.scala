package me.binwang.rss.model

import me.binwang.rss.model.ArticleOrder.ArticleOrder
import me.binwang.rss.model.FetchStatus.FetchStatus
import me.binwang.rss.model.ID.ID

import java.time.ZonedDateTime
import scala.concurrent.duration.DurationInt

object FetchStatus extends Enumeration {
  type FetchStatus = Value
  val
  SCHEDULED,
  PENDING,
  FETCHING,
  PAUSED
  = Value
}

object SourceID {
  def apply(xmlUrl: String): ID = ID.hash(xmlUrl)
}

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
