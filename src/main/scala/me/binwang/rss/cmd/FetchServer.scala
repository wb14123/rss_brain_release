package me.binwang.rss.cmd

import cats.effect.unsafe.IORuntime
import cats.effect.{ExitCode, IO, IOApp, Resource}
import com.typesafe.config.ConfigFactory
import me.binwang.rss.fetch.fetcher.ArticleEmbeddingWorker
import me.binwang.rss.metric.MetricServer
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

import scala.concurrent.duration.DurationInt

object FetchServer extends IOApp {

  implicit val loggerFactory: LoggerFactory[IO] = Slf4jFactory.create[IO]
  private val config = ConfigFactory.load()

  private val _runtime = {
    val (compute, compDown) =
      IORuntime.createWorkStealingComputeThreadPool(
        threads = computeWorkerThreadCount,
        reportFailure = t => reportFailure(t).unsafeRunAndForget()(runtime),
        blockedThreadDetectionEnabled = blockedThreadDetectionEnabled,
        runtimeBlockingExpiration = 20.minutes,
      )

    IORuntime.builder().setCompute(compute, compDown).build
  }

  override def runtime: IORuntime = _runtime

  override def run(args: List[String]): IO[ExitCode] = {
    MetricServer()
      .flatMap { _ => BaseServer()}
      .flatMap { baseServer =>
        val workerOpt: Resource[IO, Option[ArticleEmbeddingWorker]] =
          if (!config.getBoolean("article-embedding.enabled")) {
            Resource.pure(None)
          } else {
            ArticleEmbeddingWorker(
              baseServer.articleEmbeddingTaskDao,
              baseServer.articleSearchDao,
              config.getLong("article-embedding.update-interval-millis"),
              config.getLong("article-embedding.cleanup-interval-millis"),
              config.getLong("article-embedding.timeout-millis"),
              config.getInt("article-embedding.batch-size"),
              config.getInt("article-embedding.parallelism"),
              config.getString("ai-server.host"),
              config.getInt("ai-server.port")
            ).map(Some(_))
          }
        workerOpt.evalMap { articleEmbeddingWorker =>
          baseServer.fetcher.run() &> articleEmbeddingWorker.map(_.run()).getOrElse(IO.unit)
        }
      }
      .useForever
  }

}
