package me.binwang.rss.ops.cleanup.articles

import cats.effect.IO
import com.sksamuel.elastic4s.ElasticClient
import doobie.implicits._
import com.sksamuel.elastic4s.cats.effect.instances._
import me.binwang.rss.dao.elasticsearch.{ArticleElasticDao, BaseElasticDao}
import me.binwang.rss.dao.sql.{BaseSqlDao, ConnectionPool}
import me.binwang.rss.model.{Article, ArticleContent, ArticleUserMarking, FetchStatus, Source}
import org.typelevel.log4cats.LoggerFactory

import java.time.ZonedDateTime

class ArticlesCleaner(val connectionPool: ConnectionPool, val elasticClient: ElasticClient
    )(implicit val loggerFactory: LoggerFactory[IO]) extends BaseSqlDao with BaseElasticDao {

  import dbCtx._
  private implicit val articleContentSchema: dbCtx.SchemaMeta[ArticleContent] = schemaMeta[ArticleContent]("article_contents")

  private val logger = LoggerFactory.getLoggerFromClass[IO](this.getClass)

  private def deleteOldArticleMarks(fetchCompletedAtBefore: ZonedDateTime): IO[Long] = {
    run(quote {
      query[ArticleUserMarking]
        .filter(a => oldArticleQuery(fetchCompletedAtBefore).contains(a.articleID))
        .delete
    }).transact(xa)
  }

  private def deleteOldArticleContent(fetchCompletedAtBefore: ZonedDateTime): IO[Long] = {
    run(quote {
      query[ArticleContent]
        .filter(a => oldArticleQuery(fetchCompletedAtBefore).contains(a.id))
        .delete
    }).transact(xa)
  }

  private def oldArticleQuery(fetchCompletedAtBefore: ZonedDateTime) = quote {
    sourceQuery(fetchCompletedAtBefore)
      .join(query[Article])
      .on(_ == _.sourceID)
      .map(_._2.id)
  }

  private def deleteOldArticles(fetchCompletedAtBefore: ZonedDateTime): IO[Long] = {
    run(quote {
      query[Article]
        .filter(a => sourceQuery(fetchCompletedAtBefore).contains(a.sourceID))
        .delete
    }).transact(xa)
  }

  private def sourceQuery(fetchCompletedAtBefore: ZonedDateTime) = quote {
    query[Source]
      .filter(_.fetchStatus == lift(FetchStatus.PAUSED))
      .filter(_.fetchCompletedAt.exists(_ < lift(fetchCompletedAtBefore)))
      .map(_.id)
  }


  def deleteOld(fetchCompletedAtBefore: ZonedDateTime): IO[Unit] = {
    for {
      _ <- logger.info(s"Start to delete old articles ...")
      deletedMarks <- deleteOldArticleMarks(fetchCompletedAtBefore)
      _ <- logger.info(s"Deleted $deletedMarks article marks.")
      deletedContent <- deleteOldArticleContent(fetchCompletedAtBefore)
      _ <- logger.info(s"Deleted $deletedContent article content.")
      deletedArticles <- deleteOldArticles(fetchCompletedAtBefore)
      _ <- logger.info(s"Deleted $deletedArticles articles.")
    } yield ()
  }

  private def deleteElasticArticles(sourceIDs: Seq[String]): IO[Long] = {
    import com.sksamuel.elastic4s.ElasticDsl._
    elasticClient.execute {
      deleteByQuery (ArticleElasticDao.indexName, termsQuery ("sourceID", sourceIDs.toSet))
      //  .requestsPerSecond (100)
    } .map(_.result.swap.toOption.get.deleted)
  }

  private def cleanElasticStorage(): IO[Unit] = {
    import com.sksamuel.elastic4s.ElasticDsl._
    elasticClient.execute {
      forceMerge (ArticleElasticDao.indexName)
    }.map(_ => ())
  }

  private def getOldSourcesIDs(fetchCompletedAtBefore: ZonedDateTime): fs2.Stream[IO, String] = {
    stream(sourceQuery(fetchCompletedAtBefore)).transact(xa)
  }

  def deleteBySourceIDsBatch(fetchCompletedAtBefore: ZonedDateTime, batchSize: Int): IO[Unit] = {
    val sourceIDs = getOldSourcesIDs(fetchCompletedAtBefore)
    sourceIDs.chunkN(batchSize).evalScan(0L) { (total, idChunk) =>
      for {
        deleted <- deleteElasticArticles(idChunk.toList)
        next = deleted + total
        _ <- logger.info(s"Deleted $next articles")
        _ <- logger.info(s"Start to cleanup ElasticSearch storage ...")
        _ <- cleanElasticStorage()
        _ <- logger.info(s"Cleaned up ElasticSearch storage")
      } yield next
    }.compile.drain
  }

  override def table: String = ""
  override protected val indexName: String = ""
  override def createTable(): IO[Unit] = IO.unit
  override def dropTable(): IO[Unit] = IO.unit
  override def deleteAll(): IO[Unit] = IO.unit
}
