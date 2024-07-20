package me.binwang.rss.metric

import cats.effect.IO
import io.prometheus.client.{Counter, Summary}

import scala.concurrent.duration.FiniteDuration

object MetricReporter {

  private val fetchedSourceCounter = Counter.build().name("fetched_source_total")
    .help("Total fetched source").register()

  private val fetchedSourceErrorCounter = Counter.build().name("fetched_source_errors")
    .help("Error count for source fetch").labelNames("error_type").register()

  private val fetchedArticleErrorCounter = Counter.build().name("fetched_article_errors")
    .help("Error count for article fetch").labelNames("error_type").register()

  private val updateArticleCacheHitCounter = Counter.build().name("update_article_cache_hit_total")
    .help("Total cache hit count when update article").register()

  private val updateArticleCacheMissCounter = Counter.build().name("update_article_cache_miss_total")
    .help("Total cache miss count when update article").register()

  private val updateArticleContentCacheHitCounter = Counter.build().name("update_article_content_cache_hit_total")
    .help("Total cache hit count when update article content").register()

  private val updateArticleContentCacheMissCounter = Counter.build().name("update_article_content_cache_miss_total")
    .help("Total cache miss count when update article content").register()

  private val updateArticleEmbeddingCounter = Counter.build().name("update_article_embedding_total")
    .help("Total count for updating article embedding").register()

  private val updateArticleEmbeddingErrors = Counter.build().name("update_article_embedding_error")
    .help("Error count for updating article embedding").labelNames("error_type").register()

  private val methodTimeSummary = Summary.build()
    .name("method_time")
    .help("Time used for each method wrapped by TimedIO or TimedStream")
    .labelNames("method_name")
    .quantile(0.5, 0.01)
    .quantile(0.95, 0.005)
    .quantile(0.99, 0.005)
    .register();

  def countFetchedSourceSuccess(): IO[Unit] = {
    IO(fetchedSourceCounter.inc())
  }

  def countFetchedSourceError(e: Throwable): IO[Unit] = IO {
    fetchedSourceErrorCounter.labels(e.getClass.getName).inc()
    fetchedSourceCounter.inc()
  }

  def countUpdateArticle(cacheHit: Boolean): IO[Unit] = IO {
    if (cacheHit) {
      updateArticleCacheHitCounter.inc()
    } else {
      updateArticleCacheMissCounter.inc()
    }
  }

  def countUpdateArticleContent(cacheHit: Boolean): IO[Unit] = IO {
    if (cacheHit) {
      updateArticleContentCacheHitCounter.inc()
    } else {
      updateArticleContentCacheMissCounter.inc()
    }
  }

  def countFetchArticleError(e: Throwable): IO[Unit] = IO {
    fetchedArticleErrorCounter.labels(e.getClass.getName).inc()
  }

  def countUpdateArticleEmbedding(): IO[Unit] = IO {
    updateArticleEmbeddingCounter.inc()
  }

  def countUpdateArticleEmbeddingError(e: Throwable): IO[Unit] = IO {
    updateArticleEmbeddingErrors.labels(e.getClass.getName).inc()
  }

  def methodTime(methodName: String, time: FiniteDuration): IO[Unit] = IO {
    methodTimeSummary.labels(methodName).observe(time.toMillis)
  }

}
