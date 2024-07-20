package me.binwang.rss.fetch.fetcher

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import me.binwang.rss.dao.redis.{ArticleHashCheckDao, RedisCommand}
import me.binwang.rss.dao.sql.{ArticleContentSqlDao, ArticleEmbeddingTaskSqlDao, ArticleSqlDao, SourceSqlDao}
import me.binwang.rss.fetch.crawler.{Crawler, HttpCrawler}
import me.binwang.rss.generator.ConnectionPoolManager.connectionPool
import me.binwang.rss.model.{FetchStatus, SourceID}
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer.startClientAndServer
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

import java.time.ZonedDateTime

class AdhocFetcherSpec extends AnyFunSpec with BeforeAndAfterEach with BeforeAndAfterAll with Matchers {

  implicit val ioRuntime: IORuntime = IORuntime.global

  implicit val loggerFactory: LoggerFactory[IO] = Slf4jFactory.create[IO]
  private val redisClient: RedisCommand = RedisCommand().allocated.unsafeRunSync()._1
  private val sourceDao = new SourceSqlDao()
  private val articleDao = new ArticleSqlDao
  private val articleContentDao = new ArticleContentSqlDao
  private val articleHashDao: ArticleHashCheckDao = new ArticleHashCheckDao(redisClient, false)
  private val articleEmbeddingTaskDao = new ArticleEmbeddingTaskSqlDao()
  private val updater = new FetchUpdater(sourceDao, articleDao, articleContentDao, articleHashDao, articleEmbeddingTaskDao)
  private var crawler: Crawler = _
  private var fetcher: BackgroundFetcher = _
  private var mockServer: ClientAndServer = _

  private val url = "http://localhost:9998/feed.xml"
  private val sourceID = SourceID(url)

  override def beforeAll(): Unit = {
    val createTables = for {
      _ <- sourceDao.dropTable().flatMap(_ => sourceDao.createTable())
      _ <- articleDao.dropTable().flatMap(_ => articleDao.createTable())
      _ <- articleContentDao.dropTable().flatMap(_ => articleContentDao.createTable())
      _ <- articleHashDao.dropTable()
      _ <- articleEmbeddingTaskDao.dropTable()
      _ <- articleEmbeddingTaskDao.createTable()
    } yield ()
    createTables.unsafeRunSync()
  }

  override def beforeEach(): Unit = {
    crawler = HttpCrawler().allocated.unsafeRunSync()._1
    fetcher = BackgroundFetcher(crawler, sourceDao, updater, 10).unsafeRunSync()

    mockServer = startClientAndServer(9998)
    mockServer.when(request().withPath("/404")).respond(
      response("404 not found").withStatusCode(404))
  }


  override def afterEach(): Unit = {
    if (mockServer != null) {
      mockServer.stop()
    }
  }

  private def createValidFeed(file: String = "test-feed.xml"): Unit = {
    mockServer.when(request().withPath("/feed.xml")).respond(
      response()
        .withStatusCode(200)
        .withHeader("Content-Type", "text/html; charset=utf-8")
        .withBody(scala.io.Source.fromResource(file).mkString)
    )
  }

  private def createInvalidFeed(): Unit = {
    mockServer.when(request().withPath("/feed.xml")).respond(
      response("404 not found").withStatusCode(404))
  }

  describe("AdhocFetcher") {

    it("should get feed") {
      createValidFeed()
      fetcher.adhocFetch(url).unsafeRunSync()
      val source = sourceDao.get(sourceID).unsafeRunSync().get
      source.fetchStatus shouldBe FetchStatus.SCHEDULED
      articleDao.listBySource(sourceID, 10, ZonedDateTime.now()).compile.toList.unsafeRunSync().size should be > 0
    }

    it("should get feed again") {
      createValidFeed()
      fetcher.adhocFetch(url).unsafeRunSync()
      val source = sourceDao.get(sourceID).unsafeRunSync().get
      source.fetchStatus shouldBe FetchStatus.SCHEDULED
      articleDao.listBySource(sourceID, 10, ZonedDateTime.now()).compile.toList.unsafeRunSync().size should be > 0
    }

    it("should handle failure") {
      val url = "http://localhost:9998/404"
      fetcher.adhocFetch(url).unsafeRunSync().isDefined shouldBe true
    }

    it("should handle failure with the same url") {
      createInvalidFeed()
      fetcher.adhocFetch(url).unsafeRunSync().isDefined shouldBe true
      val source = sourceDao.get(sourceID).unsafeRunSync().get
      source.fetchFailedMsg should not be None
    }

    it("should clean failure message after success") {
      createValidFeed()
      fetcher.adhocFetch(url).unsafeRunSync()
      val source = sourceDao.get(sourceID).unsafeRunSync().get
      List(None, Some("")) should contain (source.fetchFailedMsg)
    }

    it("should update source") {
      createValidFeed("test-feed2.xml")
      fetcher.adhocFetch(url).unsafeRunSync()
      val source = sourceDao.get(sourceID).unsafeRunSync().get
      source.title.get shouldBe "4G Spaces"
    }

  }

}
