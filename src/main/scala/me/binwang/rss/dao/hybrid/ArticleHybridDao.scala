package me.binwang.rss.dao.hybrid

import cats.effect.IO
import me.binwang.rss.dao.ArticleDao
import me.binwang.rss.dao.elasticsearch.ArticleElasticDao
import me.binwang.rss.dao.redis.ArticleHashCheckDao
import me.binwang.rss.dao.sql.ArticleSqlDao
import me.binwang.rss.metric.MetricReporter
import me.binwang.rss.model.ID.ID
import me.binwang.rss.model.{Article, ArticleWithUserMarking}
import org.typelevel.log4cats.LoggerFactory

import java.time.ZonedDateTime

// if articleHashDaoOpt is None, skip the hash check before insertOrUpdate
class ArticleHybridDao(val articleSqlDao: ArticleSqlDao, val articleElasticDao: ArticleElasticDao,
    val articleHashDaoOpt: Option[ArticleHashCheckDao])(implicit val loggerFactory: LoggerFactory[IO]) extends ArticleDao {

  private val logger = LoggerFactory.getLoggerFromClass[IO](this.getClass)

  override def createTable(): IO[Unit] = {
    for {
      _ <- articleSqlDao.createTable()
      _ <- articleElasticDao.createTable()
    } yield ()
  }

  override def dropTable(): IO[Unit] = {
    for {
      _ <- articleHashDaoOpt.map(_.dropTable()).getOrElse(IO.unit)
      _ <- articleElasticDao.dropTable()
      _ <- articleSqlDao.dropTable()
    } yield ()
  }


  override def deleteAll(): IO[Unit] = {
    for {
      _ <- articleHashDaoOpt.map(_.dropTable()).getOrElse(IO.unit)
      _ <- articleElasticDao.deleteAll()
      _ <- articleSqlDao.deleteAll()
    } yield ()
  }

  override def get(id: ID): IO[Option[Article]] = articleSqlDao.get(id)

  override def listBySource(sourceID: ID, size: Int, postedBefore: ZonedDateTime, articleID: ID): fs2.Stream[IO, Article] =
    articleSqlDao.listBySource(sourceID, size, postedBefore, articleID)

  override def listBySourceWithUserMarking(sourceID: ID, size: Int, postedBefore: ZonedDateTime, articleID: ID,
      userID: String, read: Option[Boolean], bookmarked: Option[Boolean],
      deleted: Option[Boolean] = Some(false)): fs2.Stream[IO, ArticleWithUserMarking] =
    articleSqlDao.listBySourceWithUserMarking(sourceID, size, postedBefore, articleID, userID, read, bookmarked, deleted)

  override def listByFolder(folderID: String, size: Int, postedBefore: ZonedDateTime, articleID: ID
      ): fs2.Stream[IO, Article] = articleSqlDao.listByFolder(folderID, size, postedBefore, articleID)

  override def listByFolderWithUserMarking(folderID: String, size: Int, postedBefore: ZonedDateTime, articleID: ID,
      userID: String, read: Option[Boolean], bookmarked: Option[Boolean],
      deleted: Option[Boolean] = Some(false)): fs2.Stream[IO, ArticleWithUserMarking] =
    articleSqlDao.listByFolderWithUserMarking(folderID, size, postedBefore, articleID, userID, read, bookmarked, deleted)

  override def listByUser(userID: String, size: Int, postedBefore: ZonedDateTime, articleID: ID): fs2.Stream[IO, Article] =
    articleSqlDao.listByUser(userID, size, postedBefore, articleID)

  override def listByUserWithUserMarking(userID: String, size: Int, postedBefore: ZonedDateTime, articleID: ID,
      read: Option[Boolean], bookmarked: Option[Boolean],
      deleted: Option[Boolean] = Some(false)): fs2.Stream[IO, ArticleWithUserMarking] =
    articleSqlDao.listByUserWithUserMarking(userID, size, postedBefore, articleID, read, bookmarked, deleted)

  override def insertOrUpdate(article: Article): IO[Boolean] = {
    // make sure all indexed articles are in the database, some articles not indexed on error is acceptable

    val insert = for {
      _ <- logger.debug(s"Article changed for ${article.id}, will insert to db")
      _ <- articleSqlDao.insertOrUpdate(article)
      _ <- articleElasticDao.insertOrUpdate(article)
      _ <- MetricReporter.countUpdateArticle(false)
    } yield true

    articleHashDaoOpt match {
      case None => insert
      case Some(articleHashDao) =>
        articleHashDao.exists(article).flatMap {
          case true =>
            for {
              _ <- logger.debug(s"Article doesn't change for ${article.id}, skip insert into db")
              _ <- MetricReporter.countUpdateArticle(true)
            } yield false
          case false =>
            for {
              _ <- insert
              _ <- articleHashDao.insertOrUpdate(article)
            } yield true
        }
    }
  }

  override def listBySourceOrderByScoreWithUserMarking(sourceID: ID, size: Int, maxScore: Double, articleID: ID,
      userID: String, read: Option[Boolean], bookmarked: Option[Boolean],
      deleted: Option[Boolean] = Some(false)): fs2.Stream[IO, ArticleWithUserMarking] = {
    articleSqlDao.listBySourceOrderByScoreWithUserMarking(
      sourceID, size, maxScore, articleID, userID, read, bookmarked, deleted)
  }

  override def listByFolderOrderByScoreWithUserMarking(folderID: String, size: Int, maxScore: Double, articleID: ID,
      userID: String, read: Option[Boolean], bookmarked: Option[Boolean],
      deleted: Option[Boolean] = Some(false)): fs2.Stream[IO, ArticleWithUserMarking] = {
    articleSqlDao.listByFolderOrderByScoreWithUserMarking(
      folderID, size, maxScore, articleID, userID, read, bookmarked, deleted)
  }
}
