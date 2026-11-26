package me.binwang.rss.metric

import cats.effect.IO
import io.prometheus.metrics.core.metrics.{Counter, Summary}

import scala.concurrent.duration.FiniteDuration

object MetricReporter {

  private val fetchedSourceCounter = Counter.builder().name("fetched_source_total")
    .help("Total fetched source").register()

  private val fetchedSourceErrorCounter = Counter.builder().name("fetched_source_errors")
    .help("Error count for source fetch").labelNames("error_type").register()

  private val fetchedArticleErrorCounter = Counter.builder().name("fetched_article_errors")
    .help("Error count for article fetch").labelNames("error_type").register()

  private val updateArticleCacheHitCounter = Counter.builder().name("update_article_cache_hit_total")
    .help("Total cache hit count when update article").register()

  private val updateArticleCacheMissCounter = Counter.builder().name("update_article_cache_miss_total")
    .help("Total cache miss count when update article").register()

  private val articleNotUpdatedCounter = Counter.builder().name("article_not_updated_total")
    .help("Article not inserted or updated when request, can when there is a hash collision").register()

  private val updateArticleContentCacheHitCounter = Counter.builder().name("update_article_content_cache_hit_total")
    .help("Total cache hit count when update article content").register()

  private val updateArticleContentCacheMissCounter = Counter.builder().name("update_article_content_cache_miss_total")
    .help("Total cache miss count when update article content").register()

  private val updateArticleEmbeddingCounter = Counter.builder().name("update_article_embedding_total")
    .help("Total count for updating article embedding").register()

  private val updateArticleEmbeddingErrors = Counter.builder().name("update_article_embedding_error")
    .help("Error count for updating article embedding").labelNames("error_type").register()

  private val methodTimeSummary = Summary.builder()
    .name("method_time")
    .help("Time used for each method wrapped by TimedIO or TimedStream")
    .labelNames("method_name")
    .quantile(0.5, 0.01)
    .quantile(0.95, 0.005)
    .quantile(0.99, 0.005)
    .register()

  def countFetchedSourceSuccess(): IO[Unit] = {
    IO(fetchedSourceCounter.inc())
  }

  def countFetchedSourceError(e: Throwable): IO[Unit] = IO {
    fetchedSourceErrorCounter.labelValues(e.getClass.getName).inc()
    fetchedSourceCounter.inc()
  }

  def countUpdateArticle(cacheHit: Boolean): IO[Unit] = IO {
    if (cacheHit) {
      updateArticleCacheHitCounter.inc()
    } else {
      updateArticleCacheMissCounter.inc()
    }
  }

  def countArticleNotUpdated(): IO[Unit] = IO {
    articleNotUpdatedCounter.inc()
  }

  def countUpdateArticleContent(cacheHit: Boolean): IO[Unit] = IO {
    if (cacheHit) {
      updateArticleContentCacheHitCounter.inc()
    } else {
      updateArticleContentCacheMissCounter.inc()
    }
  }

  def countFetchArticleError(e: Throwable): IO[Unit] = IO {
    fetchedArticleErrorCounter.labelValues(e.getClass.getName).inc()
  }

  def countUpdateArticleEmbedding(): IO[Unit] = IO {
    updateArticleEmbeddingCounter.inc()
  }

  def countUpdateArticleEmbeddingError(e: Throwable): IO[Unit] = IO {
    updateArticleEmbeddingErrors.labelValues(e.getClass.getName).inc()
  }

  def methodTime(methodName: String, time: FiniteDuration): IO[Unit] = IO {
    methodTimeSummary.labelValues(methodName).observe(time.toMillis.toDouble)
  }

}
