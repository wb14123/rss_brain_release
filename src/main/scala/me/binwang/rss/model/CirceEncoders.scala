package me.binwang.rss.model

import io.circe.{Decoder, Encoder}
import me.binwang.rss.model.ArticleListLayout.ArticleListLayout
import me.binwang.rss.model.ArticleOrder.ArticleOrder

import java.time.{Instant, ZoneId, ZonedDateTime}
import scala.util.Try

object CirceEncoders {

  implicit val encodeDatetime: Encoder[ZonedDateTime] = Encoder.encodeLong.contramap[ZonedDateTime](
    _.toInstant.toEpochMilli)

  implicit val decodeDatetime: Decoder[ZonedDateTime] = Decoder.decodeLong.emapTry { timestamp =>
    Try(ZonedDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault()))
  }

  implicit val encodeArticleOrder: Encoder[ArticleOrder] = Encoder.encodeString.contramap[ArticleOrder](_.toString)
  implicit val decodeArticleOrder: Decoder[ArticleOrder] = Decoder.decodeString
    .emapTry{s => Try(ArticleOrder.withName(s))}

  implicit val encodeArticleListLayout: Encoder[ArticleListLayout] = Encoder.encodeString
    .contramap[ArticleListLayout](_.toString)
  implicit val decodeArticleListLayout: Decoder[ArticleListLayout] = Decoder.decodeString
    .emapTry{s => Try(ArticleListLayout.withName(s))}


}
