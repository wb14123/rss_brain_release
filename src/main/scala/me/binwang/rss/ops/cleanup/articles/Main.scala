package me.binwang.rss.ops.cleanup.articles

import cats.effect.{ExitCode, IO, IOApp}
import me.binwang.rss.dao.elasticsearch.ElasticSearchClient
import me.binwang.rss.dao.sql.{ConnectionPool, IsolationLevel}
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

import java.time.ZonedDateTime

object Main extends IOApp {

  implicit val loggerFactory: LoggerFactory[IO] = Slf4jFactory.create[IO]

  override def run(args: List[String]): IO[ExitCode] = {
    // delete things older than 6 months
    val deleteBefore = ZonedDateTime.now().minusMonths(6)
    val esClient = ElasticSearchClient()

    ConnectionPool(IsolationLevel.READ_COMMITTED).evalMap { connPool =>
      val cleaner = new ArticlesCleaner(connPool, esClient)
      cleaner.deleteBySourceIDsBatch(deleteBefore, 500) >> cleaner.deleteOld(deleteBefore)
    }.useForever

  }

}
