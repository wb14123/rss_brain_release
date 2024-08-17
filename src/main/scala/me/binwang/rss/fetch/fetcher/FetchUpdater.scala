package me.binwang.rss.fetch.fetcher

import cats.effect._
import cats.implicits._
import me.binwang.rss.dao.redis.ArticleHashCheckDao
import me.binwang.rss.dao.{ArticleContentDao, ArticleDao, ArticleEmbeddingTaskDao, SourceDao}
import me.binwang.rss.metric.MetricReporter
import me.binwang.rss.model._
import me.binwang.rss.parser.SourceParser
import org.typelevel.log4cats.LoggerFactory

import java.io.InputStream
import java.time.{ZoneId, ZonedDateTime}
import scala.util.Random

class FetchUpdater(
    val sourceDao: SourceDao,
    val articleDao: ArticleDao,
    val articleContentDao: ArticleContentDao,
    val articleHashCheckDao: ArticleHashCheckDao,
    val articleEmbeddingTaskDao: ArticleEmbeddingTaskDao,
  )(implicit val loggerFactory: LoggerFactory[IO]) {

  private val logger = LoggerFactory.getLoggerFromClass[IO](this.getClass)

  def updateAtStart(url: String, needExist: Boolean): IO[Unit] = {
    val id = SourceID(url)
    sourceDao.update(id, SourceUpdater(
      fetchStatus = Some(FetchStatus.FETCHING),
    ))
    .flatMap { updated =>
      if (!updated && needExist) {
        IO.raiseError(new Exception(s"Failed to update source as started. id: $id, url: $url"))
      } else {
        IO.unit
      }
    }
  }

  def handleResult(url: String, fetchResult: Resource[IO, InputStream]): IO[Unit] = {
    for {
      _ <- logger.debug(s"Fetched source from $url")
      result <- SourceParser.parse(url, fetchResult)
      _ <- logger.debug(s"Parsed source from $url")
      _ <- handleSource(url, result._1)
      _ <- result._2.filter(_.isSuccess).map(_.get).parTraverse(handleArticle(url, _))
      _ <- handleArticlesFailure(url, result._2.filter(_.isFailure).map(_.failed.get))
      _ <- MetricReporter.countFetchedSourceSuccess()
      _ <- logger.debug(s"Handled source fetch from $url")
    } yield ()
  }

  def handleFailure(url: String, e: Throwable, isArticleError: Boolean = false): IO[Unit] = {
    val id = SourceID(url)
    sourceDao.get(id).flatMap {
      case None =>
        logger.warn(e)(s"Cannot find source $id to update failure")
      case Some(originalSource) => for {
        _ <- logger.error(e)(s"Failed to fetch source. id: $id: url: $url")
        nowInstant <- Clock[IO].realTimeInstant
        now = ZonedDateTime.ofInstant(nowInstant, ZoneId.systemDefault())
        sourceUpdater <-  IO.pure{
          val errorCount = if ((originalSource.fetchErrorCount + 1) <= 0) {
            Int.MaxValue
          } else if(!isArticleError) {
            originalSource.fetchErrorCount + 1
          } else {
            originalSource.fetchErrorCount // don't add fetch error count if parsing article failed
          }
          SourceUpdater(
            fetchScheduledAt = Some(now.plusSeconds(
              getFetchDelayInSeconds(originalSource.copy(fetchErrorCount = errorCount)))),
            fetchCompletedAt = Some(Some(now)),
            fetchStatus = Some(FetchStatus.SCHEDULED),
            fetchFailedMsg = Some(Some(e.getMessage)),
            fetchErrorCount = Some(errorCount),
          )
        }
        _ <- sourceDao.update(originalSource.id, sourceUpdater)
      } yield ()
    }
      .handleErrorWith { err =>
        logger.error(err)(s"Error to save source failed state. url: $url")
      }
      .flatMap { _ =>
        MetricReporter.countFetchedSourceError(e)
      }
  }

  private def handleSource(url: String, parsedSource: Source): IO[Unit] = {
    /*
    Get and insert/update can be in different transaction. if another thread inserted the source after we get source
    as None, the insert will be ignored. So the earlier transaction might update at last, but that's Okay since it's okay
    to have a slightly stale source. The source may also be deleted so the update will fail.
     */
    sourceDao.get(ID.hash(url))
      .flatMap { s =>
        logger.debug(s"Fetched source ${parsedSource.xmlUrl}").map(_ => s)
      }
      .flatMap {
        case None => sourceDao.insert(parsedSource)
        /*
        Check source xml url to avoid hash conflict. This can still happens since it's not in the same transaction as the
        update. But the chance of a hash conflict and a transaction conflict happens at the same time is very small.
        */
        case Some(originalSource) if originalSource.xmlUrl.equals(parsedSource.xmlUrl) => for {
          nowInstant <- Clock[IO].realTimeInstant
          now = ZonedDateTime.ofInstant(nowInstant, ZoneId.systemDefault())
          sourceUpdater <- IO.pure(SourceUpdater(
            title = if (originalSource.title == parsedSource.title) None else Some(parsedSource.title),
            htmlUrl = if (originalSource.htmlUrl == parsedSource.htmlUrl) None else Some(parsedSource.htmlUrl),
            description = if (originalSource.description == parsedSource.description) None else Some(parsedSource.description),
            fetchScheduledAt = Some(now.plusSeconds(getFetchDelayInSeconds(originalSource.copy(fetchErrorCount = 0)))),
            fetchCompletedAt = Some(Some(now)),
            fetchStatus = Some(FetchStatus.SCHEDULED),
            fetchFailedMsg = Some(None),
            fetchErrorCount = Some(0),
            showTitle = Some(parsedSource.showTitle),
            showFullArticle = Some(parsedSource.showFullArticle),
            showMedia = Some(parsedSource.showMedia),
            articleOrder = Some(parsedSource.articleOrder),
            iconUrl = Some(parsedSource.iconUrl),
          ))
          _ <- sourceDao.update(originalSource.id, sourceUpdater)
        } yield ()
        case Some(originalSource) =>
          IO.raiseError(SourceIDConflictError(originalSource, parsedSource))
      }
      .map(_ => ())
  }

  private def getFetchDelayInSeconds(source: Source): Long = {
    // typical source delay is 1 hour, so this would be 1 week max delay
    val maxDelayTimes = 24 * 7
    val delayTimes = Math.min(source.fetchErrorCount + 1, maxDelayTimes)
    val fetchDelayMillis = source.fetchDelayMillis * delayTimes
    // 10 minutes random range
    val range = Math.min(fetchDelayMillis, 1000 * 60 * 10) / 2
    val delayMillis = Random.between(fetchDelayMillis - range, fetchDelayMillis + range)
    delayMillis / 1000
  }

  private def handleArticlesFailure(url: String, errs: Seq[Throwable]): IO[Unit] = {
    if (errs.isEmpty) {
      IO.pure()
    } else {
      val err = ParseArticleError(url, errs)
      for {
        _ <- errs.map { e => for {
          _ <- logger.warn(e)(s"Failed to parse article, source url: $url")
          _ <- MetricReporter.countFetchArticleError(e)
        } yield ()}.parSequence
        _ <- handleFailure(url, err)
      } yield ()
    }
  }

  private def handleArticle(url: String, articleWithContent: FullArticle): IO[Unit] = {
    /*
     Check article first to avoid hash conflict. This can still happens since it's not in the same transaction as the
     insert. But the chance of a hash conflict and a transaction conflict happens at the same time is very small.
     */
    logger.info(s"handle article ${articleWithContent.article.id}") >> articleHashCheckDao.exists(articleWithContent.article).flatMap {
      case true =>
        for {
          _ <- logger.debug(s"Article doesn't change for ${articleWithContent.article.id}, skip insert into db")
          _ <- MetricReporter.countUpdateArticle(true)
          // try to insert/update content since it maybe updated
          _ <- articleContentDao.insertOrUpdate(ArticleContent(articleWithContent.article.id, articleWithContent.content))
        } yield ()
      case false =>
        for {
          _ <- insertArticle(articleWithContent)
          nowInstant <- Clock[IO].realTimeInstant
          now = ZonedDateTime.ofInstant(nowInstant, ZoneId.systemDefault())
          schedule <- articleEmbeddingTaskDao.schedule(ArticleEmbeddingTask(
            articleWithContent.article.id,
            articleWithContent.article.title,
            now,
          ))
        } yield schedule
    }.handleErrorWith(err => handleArticlesFailure(url, Seq(err)))
  }

  private def insertArticle(articleWithContent: FullArticle): IO[Unit] = {
    for {
      /*
       Insert article first may result content not inserted. But this is a trade-off for hash conflict.
       Content should be updated for next fetch.
       If inserted is false, it means there is a hash conflict, do not update content or hash check
       */
      _ <- logger.info(s"Inserting article ${articleWithContent.article.id}")
      inserted <- articleDao.insertOrUpdate(articleWithContent.article)
      _ <- if (inserted) IO.unit else
        articleDao.get(articleWithContent.article.id).flatMap { articleOpt =>
          IO.raiseError(ArticleIDConflictError(articleOpt.get, articleWithContent.article))
        }
      _ <- articleContentDao.insertOrUpdate(ArticleContent(articleWithContent.article.id, articleWithContent.content))
      _ <- articleHashCheckDao.insertOrUpdate(articleWithContent.article)
    } yield ()
  }

}
