package me.binwang.rss.dao.hybrid

import cats.effect.IO
import me.binwang.rss.dao.ArticleContentDao
import me.binwang.rss.dao.elasticsearch.ArticleContentElasticDao
import me.binwang.rss.dao.redis.ArticleContentHashCheckDao
import me.binwang.rss.dao.sql.ArticleContentSqlDao
import me.binwang.rss.metric.MetricReporter
import me.binwang.rss.model.ArticleContent
import me.binwang.rss.model.ID.ID
import org.typelevel.log4cats.LoggerFactory

class ArticleContentHybridDao(val articleContentSqlDao: ArticleContentSqlDao,
    val articleContentElasticDao: ArticleContentElasticDao,
    val articleContentHashDao: ArticleContentHashCheckDao)
    (implicit val loggerFactory: LoggerFactory[IO]) extends ArticleContentDao {

  private val logger = LoggerFactory.getLoggerFromClass[IO](this.getClass)

  override def createTable(): IO[Unit] = {
    for {
      _ <- articleContentSqlDao.createTable()
      _ <- articleContentElasticDao.createTable()
    } yield ()
  }

  override def dropTable(): IO[Unit] = {
    for {
      _ <- articleContentHashDao.dropTable()
      _ <- articleContentSqlDao.dropTable()
      _ <- articleContentElasticDao.dropTable()
    } yield ()
  }

  override def deleteAll(): IO[Unit] = {
    for {
      _ <- articleContentHashDao.dropTable()
      _ <- articleContentSqlDao.deleteAll()
      _ <- articleContentElasticDao.deleteAll()
    } yield ()
  }

  override def get(id: ID): IO[Option[ArticleContent]] = articleContentSqlDao.get(id)

  override def insertOrUpdate(content: ArticleContent): IO[Boolean] = {
    articleContentHashDao.exists(content).flatMap {
      case true =>
        for {
          _ <- logger.debug(s"Article content doesn't change for ${content.id}, skip insert into db")
          _ <- MetricReporter.countUpdateArticleContent(true)
        } yield false
      case false =>
        for {
          _ <- logger.debug(s"Article content changed for ${content.id}, will insert to db")
          _ <- articleContentSqlDao.insertOrUpdate(content)
          _ <- articleContentElasticDao.insertOrUpdate(content)
          _ <- articleContentHashDao.insertOrUpdate(content)
          _ <- MetricReporter.countUpdateArticleContent(false)
        } yield true
    }
  }

  override def batchGet(ids: Seq[ID]): fs2.Stream[IO, ArticleContent] = {
    articleContentSqlDao.batchGet(ids)
  }
}
