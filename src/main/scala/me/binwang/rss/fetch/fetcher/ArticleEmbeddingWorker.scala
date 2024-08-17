package me.binwang.rss.fetch.fetcher
import cats.effect.std.Queue
import cats.effect.{Clock, IO, Resource}
import fs2.grpc.syntax.all._
import io.grpc.Metadata
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import me.binwang.aitext.ai_text.{GetEmbeddingRequest, SentenceTransformerFs2Grpc}
import me.binwang.rss.dao.{ArticleEmbeddingTaskDao, ArticleSearchDao}
import me.binwang.rss.metric.MetricReporter
import me.binwang.rss.model.{ArticleEmbeddingTask, ArticleEmbeddingTaskUpdater, EmbeddingUpdateStatus}
import org.typelevel.log4cats.LoggerFactory

import java.time.{ZoneId, ZonedDateTime}

object ArticleEmbeddingWorker {
  def apply(
      articleEmbeddingTaskDao: ArticleEmbeddingTaskDao,
      articleSearchDao: ArticleSearchDao,
      updateIntervalMillis: Long,
      cleanupIntervalMillis: Long,
      taskTimeoutMillis: Long,
      batchSize: Int,
      parallelism: Int,
      embeddingServerHost: String,
      embeddingServerPort: Int,
  )(implicit loggerFactory: LoggerFactory[IO]): Resource[IO, ArticleEmbeddingWorker] = {


    (for {
      channel <- NettyChannelBuilder
        .forAddress(embeddingServerHost, embeddingServerPort)
        .usePlaintext() // TODO: enable SSL?
        .resource[IO]
      stub <- SentenceTransformerFs2Grpc.stubResource[IO](channel)
    } yield stub).evalMap { stub =>
      Queue.bounded[IO, ArticleEmbeddingTask](batchSize * 2).map { queue =>
        new ArticleEmbeddingWorker(
          articleEmbeddingTaskDao,
          articleSearchDao,
          updateIntervalMillis,
          cleanupIntervalMillis,
          taskTimeoutMillis,
          batchSize,
          parallelism,
          queue,
          stub,
        )
      }
    }
  }
}

class ArticleEmbeddingWorker(
    articleEmbeddingTaskDao: ArticleEmbeddingTaskDao,
    articleSearchDao: ArticleSearchDao,
    updateIntervalMillis: Long,
    cleanupIntervalMillis: Long,
    taskTimeoutMillis: Long,
    batchSize: Int,
    parallelism: Int,
    workQueue: Queue[IO, ArticleEmbeddingTask],
    sentenceTransformerStub: SentenceTransformerFs2Grpc[IO, Metadata],
)(implicit loggerFactory: LoggerFactory[IO]) {

  private val logger = LoggerFactory.getLoggerFromClass[IO](this.getClass)

  def run(): IO[Unit] = {
    logger.info("Starting article embedding worker ...") >>
    (TimerLoop(getTasksFromDB, updateIntervalMillis) &>
      TimerLoop(cleanupFinishedTasks, cleanupIntervalMillis) &>
      TimerLoop(rescheduleTimeoutTasks, taskTimeoutMillis) &>
      updateEmbeddings()
    ).onCancel(logger.info("Stopped article embedding worker"))
  }

  private def getTasksFromDB(): IO[Boolean] = {
    Clock[IO].realTimeInstant.flatMap { nowInstant =>
      val now = ZonedDateTime.ofInstant(nowInstant, ZoneId.systemDefault())
      articleEmbeddingTaskDao
        .getTasksForUpdate(now, batchSize)
        .evalMap(workQueue.offer)
        .fold(0)((count, _) => count + 1)
        .compile
        .last
        .flatMap {
          case None | Some(0) =>
            logger.info(s"No article embeddings need to be updated. " +
                s"Sleep ${updateIntervalMillis / 1000.0}s for next fetch.").map(_ => false)
          case Some(n) =>
            logger
              .info(s"Queued $n articles for updating embeddings.")
              .map(_ => true)
        }
        .handleErrorWith { err =>
          logger.error(err)(s"Error while get articles to update embeddings, " +
            s"sleep ${updateIntervalMillis / 1000.0}s for next update").map(_ => false)
        }
    }
  }

  private def updateEmbeddings() = {
    fs2.Stream
      .fromQueueUnterminated(workQueue)
      .parEvalMap(parallelism)(handleTask)
      .compile
      .drain
      .onCancel(logger.info("Stopped update article embedding stream"))
  }

  private def handleTask(task: ArticleEmbeddingTask): IO[Unit] = {
    (for {
      _ <- logger.debug(s"Start update article embedding for ${task.articleID}")
      nowInstant <- Clock[IO].realTimeInstant
      now = ZonedDateTime.ofInstant(nowInstant, ZoneId.systemDefault())
      embedding <- getEmbedding(task.title)
      found <- articleSearchDao.updateTitleEmbedding(task.articleID, embedding)
      _ <- if (found) logger.debug(s"Updated embedding in ES for article ${task.articleID}") else
        logger.warn(s"Article not found when updating embedding in ES for article ${task.articleID}")
      _ <- articleEmbeddingTaskDao.update(task.articleID, ArticleEmbeddingTaskUpdater(
        status = Some(EmbeddingUpdateStatus.FINISHED),
        finishedAt = Some(Some(now)),
      ))
      _ <- logger.debug(s"Task finished for updating embedding, article ID: ${task.articleID}")
      _ <- MetricReporter.countUpdateArticleEmbedding()
    } yield ()).handleErrorWith { err =>
      val retryWaitMinutes = Math.min(Math.pow(2, task.retried).toInt, 60)
      logger.error(err)(s"Error to get embeddings for article ${task.articleID}, " +
          s"retried ${task.retried} times, scheduling another retry after $retryWaitMinutes minutes") >>
        articleEmbeddingTaskDao.update(task.articleID, ArticleEmbeddingTaskUpdater(
          status = Some(EmbeddingUpdateStatus.PENDING),
          finishedAt = Some(None),
          scheduledAt = Some(task.scheduledAt.plusMinutes(retryWaitMinutes)),
          retried = Some(task.retried + 1),
        )) >>
        MetricReporter.countUpdateArticleEmbeddingError(err)
    }
  }

  private def cleanupFinishedTasks(): IO[Boolean] = {
    for {
      nowInstant <- Clock[IO].realTimeInstant
      now = ZonedDateTime.ofInstant(nowInstant, ZoneId.systemDefault())
      count <- articleEmbeddingTaskDao.deleteFinishedTasks(now.minusDays(1))
      _ <- logger.info(s"Deleted $count finished article embedding tasks. " +
        s"Waiting ${cleanupIntervalMillis / 1000} seconds for next cleanup.")
    } yield false
  }

  private def rescheduleTimeoutTasks(): IO[Boolean] = {
    for {
      nowInstant <- Clock[IO].realTimeInstant
      now = ZonedDateTime.ofInstant(nowInstant, ZoneId.systemDefault())
      count <- articleEmbeddingTaskDao.rescheduleTasks(now.minusSeconds(taskTimeoutMillis / 1000), now)
      _ <- logger.info(
        s"Rescheduled $count timed out article embedding tasks. " +
          s"Waiting ${taskTimeoutMillis / 1000} seconds for next check."
      )
    } yield false
  }


  private def getEmbedding(text: String): IO[Seq[Double]] = {
    sentenceTransformerStub.getEmbedding(GetEmbeddingRequest(text), new Metadata()).map(_.values)
  }

}
