package me.binwang.rss.dao.sql

import cats.effect.IO
import doobie.Fragment
import doobie.hikari.HikariTransactor
import doobie.implicits._
import io.circe.generic.auto._
import io.circe.parser
import io.getquill.{CamelCase, NamingStrategy}
import io.getquill.doobie.DoobieContext
import me.binwang.rss.model.ArticleListLayout.ArticleListLayout
import me.binwang.rss.model.ArticleOrder.ArticleOrder
import me.binwang.rss.model.EmbeddingUpdateStatus.EmbeddingUpdateStatus
import me.binwang.rss.model.FetchStatus.FetchStatus
import me.binwang.rss.model.ID.ID
import me.binwang.rss.model.MoreLikeThisType.MoreLikeThisType
import me.binwang.rss.model.NSFWSetting.NSFWSetting
import me.binwang.rss.model._
import org.postgresql.util.PGobject

import java.sql.Types
import java.time.{ZoneId, ZoneOffset, ZonedDateTime}
import java.util.Date

class CustomDBContext[+N <: NamingStrategy](naming: N) extends DoobieContext.Postgres(naming) {
  override implicit val zonedDateTimeDecoder: Decoder[ZonedDateTime] =
    decoder((index, row, _) => ZonedDateTime
      .ofInstant(row.getObject(index, classOf[Date]).toInstant, ZoneOffset.UTC)
      .withZoneSameInstant(ZoneId.systemDefault()))

  override implicit val zonedDateTimeEncoder: Encoder[ZonedDateTime] =
    encoder(jdbcTypeOfZonedDateTime, (index, value, row) =>
      row.setObject(index, Date.from(value.withZoneSameInstant(ZoneOffset.UTC).toInstant), Types.TIMESTAMP))
}


trait BaseSqlDao {

  protected implicit val connectionPool: ConnectionPool
  protected val xa: HikariTransactor[IO] = connectionPool.xa
  protected val UUID_LENGTH = 36
  protected val IP_MAX_LENGTH = 15

  val dbCtx = new CustomDBContext(CamelCase)
  import dbCtx._

  protected implicit class ZonedDateTimeQuotes(left: ZonedDateTime) {
    def >(right: ZonedDateTime) = quote(sql"$left > $right".as[Boolean])
    def <(right: ZonedDateTime) = quote(sql"$left < $right".as[Boolean])
    def ==(right: ZonedDateTime) = quote(sql"$left == $right".as[Boolean])
    def >=(right: ZonedDateTime) = quote(sql"$left >= $right".as[Boolean])
    def <=(right: ZonedDateTime) = quote(sql"$left <= $right".as[Boolean])
  }

  protected implicit class StringQuotes(left: ID) {
    def lessThan(right: ID) = quote(sql"$left < $right".as[Boolean])
    def greaterThan(right: ID) = quote(sql"$left > $right".as[Boolean])
    def lessEqThan(right: ID) = quote(sql"$left <= $right".as[Boolean])
    def greaterEqThan(right: ID) = quote(sql"$left >= $right".as[Boolean])
  }

  protected implicit val decodeFetchStatus: MappedEncoding[String, FetchStatus] =
    MappedEncoding[String, FetchStatus](FetchStatus.withName)

  protected implicit val encodeFetchStatus: MappedEncoding[FetchStatus, String] =
    MappedEncoding[FetchStatus, String](_.toString)

  protected implicit val decodeEmbeddingUpdateStatus: MappedEncoding[String, EmbeddingUpdateStatus] =
    MappedEncoding[String, EmbeddingUpdateStatus](EmbeddingUpdateStatus.withName)

  protected implicit val encodeEmbeddingUpdateStatus: MappedEncoding[EmbeddingUpdateStatus, String] =
    MappedEncoding[EmbeddingUpdateStatus, String](_.toString)

  protected implicit val decodeMoreLikeThisType: MappedEncoding[String, MoreLikeThisType] =
    MappedEncoding[String, MoreLikeThisType](MoreLikeThisType.withName)

  protected implicit val encodeMoreLikeThisType: MappedEncoding[MoreLikeThisType, String] =
    MappedEncoding[MoreLikeThisType, String](_.toString)

  protected implicit val decodeArticleOrder: MappedEncoding[String, ArticleOrder] =
    MappedEncoding[String, ArticleOrder](ArticleOrder.withName)

  protected implicit val encodeArticleOrder: MappedEncoding[ArticleOrder, String] =
    MappedEncoding[ArticleOrder, String](_.toString)

  protected implicit val decodeArticleListLayout: MappedEncoding[String, ArticleListLayout] =
    MappedEncoding[String, ArticleListLayout](ArticleListLayout.withName)

  protected implicit val encodeArticleListLayout: MappedEncoding[ArticleListLayout, String] =
    MappedEncoding[ArticleListLayout, String](_.toString)

  protected implicit val decodeNsfwSetting: MappedEncoding[String, NSFWSetting] =
    MappedEncoding[String, NSFWSetting](NSFWSetting.withName)

  protected implicit val encodeNsfwSetting: MappedEncoding[NSFWSetting, String] =
    MappedEncoding[NSFWSetting, String](_.toString)

  protected implicit val mediaGroupsEncoder: Encoder[MediaGroups] = encoder(java.sql.Types.OTHER, (index, mediaGroups, row) => {
    val value = io.circe.syntax.EncoderOps(mediaGroups).asJson.toString()
    val pgObj = new PGobject()
    pgObj.setType("jsonb")
    pgObj.setValue(value)
    row.setObject(index, pgObj)
  })

  protected implicit val mediaGroupsDecoder: Decoder[MediaGroups] = decoder { (index, row, _) =>
    val defaultMediaGroups = MediaGroups(groups = Seq())
    val pgObj = row.getObject(index).asInstanceOf[PGobject]
    if (pgObj == null) {
      defaultMediaGroups
    } else {
      parser.parse(pgObj.getValue).flatMap(_.as[MediaGroups]).getOrElse(defaultMediaGroups)
    }
  }

  protected implicit val searchEngineEncoder: Encoder[SearchEngine] = encoder(java.sql.Types.OTHER, (index, searchEngine, row) => {
    val value = io.circe.syntax.EncoderOps(searchEngine).asJson.toString()
    val pgObj = new PGobject()
    pgObj.setType("jsonb")
    pgObj.setValue(value)
    row.setObject(index, pgObj)
  })

  protected implicit val searchEngineDecoder: Decoder[SearchEngine] = decoder { (index, row, _) =>
    val pgObj = row.getObject(index).asInstanceOf[PGobject]
    if (pgObj == null) {
      SearchEngine.DEFAULT
    } else {
      parser.parse(pgObj.getValue).flatMap(_.as[SearchEngine]).getOrElse(SearchEngine.DEFAULT)
    }
  }


  def createTable(): IO[Unit]

  def dropTable(): IO[Unit] = {
    val q =
      Fragment.const(s"drop table if exists $table")
      .update.run.map(_ => ())
    q.transact(xa)
  }

  def deleteAll(): IO[Unit] = {
    val q = {
      for {
        _ <- Fragment.const(s"set local sql_safe_updates=false").update.run
        _ <- Fragment.const(s"delete from $table").update.run
      } yield ()
    }
    q.transact(xa)
  }

  def table: String

  protected def createIndex(field: String, desc: Boolean = false, unique: Boolean = false): doobie.ConnectionIO[Int] = {
    val idxName = s"idx_${table}_$field"
    val uniqueStr = if (unique) "unique" else ""
    val order = if (desc) "desc" else ""
    val sql = s"create $uniqueStr index if not exists $idxName on $table ($field $order)"
    Fragment.const(sql).update.run
  }

  protected def createIndexWithFields(fields: Seq[(String, Boolean)], unique: Boolean = false): doobie.ConnectionIO[Int] = {
    val idxName = s"idx_${table}_${fields.map(_._1).mkString("_")}"
    val uniqueStr = if (unique) "unique" else ""
    val idx = fields.map { case (field, desc) =>
      val order = if (desc) "desc" else ""
      s"$field $order"
    }.mkString(" ,")
    val sql = s"create $uniqueStr index if not exists $idxName on $table ($idx)"
    Fragment.const(sql).update.run
  }
}
