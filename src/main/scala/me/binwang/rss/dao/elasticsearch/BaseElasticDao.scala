package me.binwang.rss.dao.elasticsearch

import cats.effect.IO
import com.sksamuel.elastic4s.ElasticClient
import com.sksamuel.elastic4s.cats.effect.instances._
import io.circe.{Decoder, Encoder}

import java.time.{Instant, ZoneId, ZonedDateTime}
import scala.util.Try

trait BaseElasticDao {

  protected implicit val elasticClient: ElasticClient
  protected val indexName: String

  // TODO: Use model.CirceEncoders instead
  implicit val encodeDatetime: Encoder[ZonedDateTime] = Encoder.encodeLong.contramap[ZonedDateTime](
    _.toInstant.toEpochMilli)

  implicit val decodeDatetime: Decoder[ZonedDateTime] = Decoder.decodeLong.emapTry { timestamp =>
    Try(ZonedDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault()))
  }

  def dropTable(): IO[Unit] = {
    import com.sksamuel.elastic4s.ElasticDsl._
    elasticClient.execute(deleteIndex(indexName)).map(_ => ())
  }

  def createTable(): IO[Unit]

  def deleteAll(): IO[Unit] = {
    dropTable().flatMap(_ => createTable())
  }
}
