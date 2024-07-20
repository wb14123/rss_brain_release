package me.binwang.rss.generator

import me.binwang.rss.model.{Source, SourceID}
import org.scalacheck.Gen

import java.time.ZonedDateTime

object Sources {

  def get(scheduledAt: Option[ZonedDateTime] = None, maybeXmlUrl: Option[String] = None,
      fetchDelayMillis: Long = 3600 * 1000): Source
  = {
    val now = ZonedDateTime.now().withNano(0)
    val genSource = for {
      title <- Gen.asciiPrintableStr
      xmlUrl <- Gen.uuid
      htmlUrl <- Gen.asciiPrintableStr
      description <- Gen.asciiPrintableStr
    } yield Source(
      id = SourceID(maybeXmlUrl.getOrElse(xmlUrl.toString)),
      title = Some(title),
      xmlUrl = maybeXmlUrl.getOrElse(xmlUrl.toString),
      htmlUrl = Some(htmlUrl),
      description = Some(description),
      fetchScheduledAt = scheduledAt.getOrElse(ZonedDateTime.now().plusHours(1).withNano(0)),
      fetchDelayMillis = fetchDelayMillis,
      importedAt = now,
      updatedAt = now,
    )
    genSource.sample.get
  }

}
