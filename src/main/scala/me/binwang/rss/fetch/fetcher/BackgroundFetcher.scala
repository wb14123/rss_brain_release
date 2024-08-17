package me.binwang.rss.fetch.fetcher

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.kernel.Clock
import cats.effect.std.Queue
import cats.syntax.parallel._
import me.binwang.rss.dao.SourceDao
import me.binwang.rss.fetch.crawler.Crawler
import org.typelevel.log4cats.LoggerFactory

import java.time.{ZoneId, ZonedDateTime}

object BackgroundFetcher {

  def apply(
      crawler: Crawler,
      sourceDao: SourceDao,
      fetchUpdater: FetchUpdater,
      batchSize: Int,
      fetchSourceIntervalMillis: Long= 60 * 1000,
      cleanTimeoutSourceIntervalMillis: Long = 5 * 60 * 1000,
      cleanTimeout: Boolean = true,
      pauseSourceIntervalMillis: Long = 24 * 3600 * 1000,
      pauseSource: Boolean = true,
  )(implicit loggerFactory: LoggerFactory[IO]): IO[BackgroundFetcher] = {
    Queue.bounded[IO, Option[String]](batchSize * 2).map { fetchQueue =>
      new BackgroundFetcher(
        crawler, sourceDao, fetchUpdater, batchSize, fetchSourceIntervalMillis, cleanTimeoutSourceIntervalMillis,
        cleanTimeout, pauseSourceIntervalMillis, pauseSource, fetchQueue
      )
    }
  }

}

class BackgroundFetcher(
    crawler: Crawler,
    sourceDao: SourceDao,
    fetchUpdater: FetchUpdater,
    batchSize: Int,
    fetchSourceIntervalMillis: Long,
    cleanTimeoutSourceIntervalMillis: Long,
    cleanTimeout: Boolean,
    pauseSourceIntervalMillis: Long,
    pauseSource: Boolean,
    fetchQueue: Queue[IO, Option[String]],
)(implicit val loggerFactory: LoggerFactory[IO]) {

  private val logger = LoggerFactory.getLoggerFromClass[IO](this.getClass)

  /**
   * Run background fetcher
   *
   * It will get FetchTasks from database and call `fetcher` to fetch the URLs.
   */
  def run(): IO[Unit]= {
    logger.info("Starting background fetcher ...") >>
    NonEmptyList.of(
      TimerLoop(getSourcesToFetch, fetchSourceIntervalMillis),
      if (cleanTimeout) TimerLoop(cleanTimeoutSources, cleanTimeoutSourceIntervalMillis) else IO.unit,
      if (pauseSource) TimerLoop(pauseSources, pauseSourceIntervalMillis) else IO.unit,
      fetchSources(),
    ).parSequence.map(_ => ())
      .onCancel(logger.info("Stopped background fetcher"))
  }

  private def getSourcesToFetch(): IO[Boolean] = {
    Clock[IO].realTimeInstant.flatMap { nowInstant =>
      val now = ZonedDateTime.ofInstant(nowInstant, ZoneId.systemDefault())
      sourceDao
        .getFetchURLs(batchSize, now)
        .map(Some(_))
        .evalMap(fetchQueue.offer)
        .fold(0)((count, _) => count + 1)
        .compile
        .last
    }
      .flatMap {
        case None | Some(0) =>
          logger.info(s"No source need to be fetched. " +
            s"Sleep ${fetchSourceIntervalMillis / 1000.0}s for next fetch.").map(_ => false)
        case Some(n) =>
          logger.info(s"Queued $n sources for fetch.").map(_ => true)
      }
      .handleErrorWith { err =>
        logger.error(err)(s"Error while get sources to fetch, " +
          s"sleep ${fetchSourceIntervalMillis / 1000.0}s for next fetch").map(_ => false)
      }
  }

  private def fetchSources(): IO[Unit] = {
    fs2.Stream.fromQueueNoneTerminated(fetchQueue)
      .parEvalMap(batchSize)(url => handleTask(url))
      .compile
      .drain
      .onCancel(logger.info("Stopped fetch source streaming"))
  }

  private def cleanTimeoutSources(): IO[Boolean] = {
    for {
      nowInstant <- Clock[IO].realTimeInstant
      now = ZonedDateTime.ofInstant(nowInstant, ZoneId.systemDefault())
      timedOut <- sourceDao.timeoutFetching(now.minusHours(1), now)
      _ <- logger.info(s"Marked $timedOut sources as timeout. " +
        s"Sleep ${cleanTimeoutSourceIntervalMillis / 1000.0}s for next timeout cleanup.")
    } yield false
  }

  private def pauseSources(): IO[Boolean] = {
    for {
      nowInstant <- Clock[IO].realTimeInstant
      now = ZonedDateTime.ofInstant(nowInstant, ZoneId.systemDefault())
      _ <- pauseSourcesNotInFolder()
      _ <- pauseSourcesForDeactivatedUsers(now)
      _ <- resumeSourcesForActiveUsers(now)
    } yield false
  }

  private def pauseSourcesNotInFolder(): IO[Boolean] = {
    sourceDao
      .pauseNotInFolderSources()
      .flatMap(count => logger.info(s"Paused $count sources not in folder, " +
        s"Sleep ${pauseSourceIntervalMillis / 1000.0}s for next check"))
      .map(_ => false)
  }

  private def pauseSourcesForDeactivatedUsers(now: ZonedDateTime): IO[Boolean] = {
    sourceDao
      .pauseSourcesForDeactivatedUsers(now)
      .flatMap(count => logger.info(s"Paused $count sources for deactivated users, " +
        s"Sleep ${pauseSourceIntervalMillis / 1000.0}s for next check"))
      .map(_ => false)
  }

  private def resumeSourcesForActiveUsers(now: ZonedDateTime): IO[Boolean] = {
    sourceDao
      .resumeSourcesForActiveUsers(now)
      .flatMap(count => logger.info(s"Resumed $count sources for active users, " +
        s"sleep ${pauseSourceIntervalMillis / 1000.0}s for next check"))
      .map(_ => false)
  }

  def adhocFetch(url:String): IO[Option[Throwable]] = {
    handleTask(url, needExist = false)
  }

  private def handleTask(url: String, needExist: Boolean = true): IO[Option[Throwable]] = {
      fetchUpdater
        .updateAtStart(url, needExist)
        .flatMap{_ => fetchUpdater.handleResult(url, crawler.fetch(url))}
        .map(_ => None)
        .handleErrorWith { err =>
          fetchUpdater
            .handleFailure(url, err)
            .map(_ => Some(err))
        }
  }

}
