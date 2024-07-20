package me.binwang.rss.generator

import me.binwang.rss.model.{Article, ArticleID, SourceID}
import org.scalacheck.Gen
import me.binwang.rss.model.ID.ID

import java.time.ZonedDateTime

object Articles {

  def get(maybeSourceID: Option[ID] = None, maybeGuid: Option[String] = None,
          maybePostedAt: Option[ZonedDateTime] = None): Article = {
    val now = ZonedDateTime.now().withNano(0)
    val genArticle = for {
      title <- Gen.asciiPrintableStr.retryUntil(_.nonEmpty)
      sourceID <- Gen.asciiPrintableStr.map(SourceID(_))
      sourceTitle <- Gen.asciiPrintableStr.retryUntil(_.nonEmpty)
      guid <- Gen.asciiPrintableStr.retryUntil(_.nonEmpty)
      link <- Gen.asciiPrintableStr.retryUntil(_.nonEmpty)
      createdAt <- Gen.choose[Int](10, 100)
      postedAt <- Gen.choose[Int](200, 10000)
      desc <- Gen.asciiPrintableStr
    } yield Article(
      id = ArticleID(maybeSourceID.getOrElse(sourceID), maybeGuid.getOrElse(guid)),
      title = title,
      sourceID = maybeSourceID.getOrElse(sourceID),
      sourceTitle = Some(sourceTitle),
      guid = maybeGuid.getOrElse(guid),
      link = link,
      createdAt = now.minusMinutes(createdAt).withNano(0),
      postedAt = maybePostedAt.getOrElse(now.minusMinutes(postedAt).withNano(0)),
      description = desc,
    )
    genArticle.sample.get
  }

}
