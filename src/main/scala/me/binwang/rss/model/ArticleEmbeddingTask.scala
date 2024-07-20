package me.binwang.rss.model

import me.binwang.rss.model.EmbeddingUpdateStatus.EmbeddingUpdateStatus
import me.binwang.rss.model.ID.ID

import java.time.ZonedDateTime


object EmbeddingUpdateStatus extends Enumeration {
  type EmbeddingUpdateStatus = Value
  val
  PENDING,
  UPDATING,
  FINISHED
  = Value
}

case class ArticleEmbeddingTask(
  articleID: ID,
  title: String,
  scheduledAt: ZonedDateTime,
  status: EmbeddingUpdateStatus = EmbeddingUpdateStatus.PENDING,
  startedAt: Option[ZonedDateTime] = None,
  finishedAt: Option[ZonedDateTime] = None,
  retried: Int = 0,
)

case class ArticleEmbeddingTaskUpdater(
  scheduledAt: Option[ZonedDateTime] = None,
  status: Option[EmbeddingUpdateStatus] = None,
  startedAt: Option[Option[ZonedDateTime]] = None,
  finishedAt: Option[Option[ZonedDateTime]] = None,
  retried: Option[Int] = None,
)
