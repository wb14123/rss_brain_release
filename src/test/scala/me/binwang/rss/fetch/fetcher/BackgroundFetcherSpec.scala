package me.binwang.rss.fetch.fetcher

import cats.effect.{FiberIO, IO}
import cats.effect.unsafe.IORuntime
import me.binwang.rss.dao.redis.{ArticleHashCheckDao, RedisCommand}
import me.binwang.rss.dao.sql.{ArticleContentSqlDao, ArticleEmbeddingTaskSqlDao, ArticleSqlDao, SourceSqlDao}
import me.binwang.rss.dao.{ArticleContentDao, ArticleDao, SourceDao}
import me.binwang.rss.fetch.crawler.{Crawler, HttpCrawler}
import me.binwang.rss.generator.ConnectionPoolManager.connectionPool
import me.binwang.rss.generator.Sources
import me.binwang.rss.model.ID.ID
import me.binwang.rss.model.{SourceID, SourceUpdater}
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer.startClientAndServer
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.scalamock.scalatest.MockFactory
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

import java.time.ZonedDateTime

class BackgroundFetcherSpec extends AnyFunSpec with BeforeAndAfterEach with BeforeAndAfterAll
  with Matchers with MockFactory {

  implicit val ioRuntime: IORuntime = IORuntime.global

  implicit val loggerFactory: LoggerFactory[IO] = Slf4jFactory.create[IO]
  private val redisClient: RedisCommand = RedisCommand().allocated.unsafeRunSync()._1
  private val sourceDao = new SourceSqlDao()
  private val articleDao = new ArticleSqlDao
  private val articleHashDao: ArticleHashCheckDao = new ArticleHashCheckDao(redisClient, false)
  private val articleContentDao = new ArticleContentSqlDao
  private val articleEmbeddingTaskDao = new ArticleEmbeddingTaskSqlDao()
  private val crawler = HttpCrawler().allocated.unsafeRunSync()._1
  private var mockServer: ClientAndServer = _
  private var fetcherRunTask: IO[FiberIO[Unit]] = _

  private val url = "http://localhost:9998/feed.xml"

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
    val clearTables = for {
      _ <- sourceDao.deleteAll()
      _ <- articleDao.deleteAll()
      _ <- articleContentDao.deleteAll()
      _ <- articleHashDao.dropTable()
      _ <- articleEmbeddingTaskDao.deleteAll()
    } yield ()
    clearTables.unsafeRunSync()

    mockServer = startClientAndServer(9998)
    mockServer.when(request().withPath("/404")).respond(
      response("404 not found").withStatusCode(404))
  }


  override def afterEach(): Unit = {
    if (mockServer != null) {
      mockServer.stop()
    }
    if (fetcherRunTask != null) {
      fetcherRunTask.flatMap(f => f.cancel >> f.join).unsafeRunSync()
    }
    Thread.sleep(100)
  }

  private def createValidFeed(path: String = "/feed.xml", file: String = "test-feed.xml"): Unit = {
    mockServer.when(request().withPath(path)).respond(
      response()
        .withStatusCode(200)
        .withHeader("Content-Type", "text/html; charset=utf-8")
        .withBody(scala.io.Source.fromResource(file).mkString)
    )
  }

  describe("Background Fetcher") {

    it("should get feed") {
      createValidFeed()
      val updater = new FetchUpdater(sourceDao, articleDao, articleContentDao, articleHashDao, articleEmbeddingTaskDao)
      val fetcher = BackgroundFetcher(crawler, sourceDao, updater, 10, cleanTimeout = false, pauseSource = false).unsafeRunSync()
      val id = SourceID(url)
      val source = Sources.get(Some(ZonedDateTime.now().minusHours(1)), Some(url))
      sourceDao.insert(source).unsafeRunSync()
      fetcherRunTask = fetcher.run().start
      fetcherRunTask.unsafeRunAndForget()
      Thread.sleep(2000)
      val insertedSource = sourceDao.get(id).unsafeRunSync().get
      insertedSource.title.get shouldBe "CBC | Top Stories News"
    }

    it("should get articles with invalid formats") {
      createValidFeed("/feed.xml", "test-feed-invalid-article.xml")
      val updater = new FetchUpdater(sourceDao, articleDao, articleContentDao, articleHashDao, articleEmbeddingTaskDao)
      val fetcher = BackgroundFetcher(crawler, sourceDao, updater, 10, cleanTimeout = false, pauseSource = false).unsafeRunSync()
      val id = SourceID(url)
      val source = Sources.get(Some(ZonedDateTime.now().minusHours(1)), Some(url))
      sourceDao.insert(source).unsafeRunSync()
      fetcherRunTask = fetcher.run().start
      fetcherRunTask.unsafeRunAndForget()
      Thread.sleep(2000)
      val insertedSource = sourceDao.get(id).unsafeRunSync().get
      insertedSource.title.get shouldBe "CBC | Top Stories News"
      articleDao.listBySource(source.id, 100, ZonedDateTime.now()).compile.toList.unsafeRunSync().length shouldBe 19
    }

    it("should wait on empty") {
      createValidFeed()
      val updater = new FetchUpdater(sourceDao, articleDao, articleContentDao, articleHashDao, articleEmbeddingTaskDao)
      val fetcher = BackgroundFetcher(crawler, sourceDao, updater, 10, 1000, cleanTimeout = false, pauseSource = false).unsafeRunSync()
      fetcherRunTask = fetcher.run().start
      fetcherRunTask.unsafeRunAndForget()
      Thread.sleep(500) // wait for the first fetch result
      val id = SourceID(url)
      val source = Sources.get(Some(ZonedDateTime.now().minusHours(1)), Some(url))
      sourceDao.get(id).unsafeRunSync() shouldBe None
      sourceDao.insert(source).unsafeRunSync()
      Thread.sleep(2000) // wait for retry get URLs and fetch the source
      val insertedSource = sourceDao.get(id).unsafeRunSync().get
      insertedSource.title.get shouldBe "CBC | Top Stories News"
    }

    it("should get multiple feed") {
      createValidFeed()
      createValidFeed("/feed2.xml", "test-feed2.xml")
      val updater = new FetchUpdater(sourceDao, articleDao, articleContentDao, articleHashDao, articleEmbeddingTaskDao)
      val fetcher = BackgroundFetcher(crawler, sourceDao, updater, 1, cleanTimeout = false, pauseSource = false).unsafeRunSync()
      val source1 = Sources.get(Some(ZonedDateTime.now().minusHours(1)), Some("http://localhost:9998/feed.xml"))
      val source2 = Sources.get(Some(ZonedDateTime.now().minusHours(1)), Some("http://localhost:9998/feed2.xml"))
      sourceDao.insert(source1).unsafeRunSync()
      sourceDao.insert(source2).unsafeRunSync()
      fetcherRunTask = fetcher.run().start
      fetcherRunTask.unsafeRunAndForget()
      Thread.sleep(2000)
      sourceDao.get(source1.id).unsafeRunSync().get.title.get shouldBe "CBC | Top Stories News"
      sourceDao.get(source2.id).unsafeRunSync().get.title.get shouldBe "4G Spaces"
    }

    it("should handle get fetch tasks failure") {
      createValidFeed()
      createValidFeed("/feed2.xml", "test-feed2.xml")

      val mockSourceDao = mock[SourceDao]
      val source1 = Sources.get(Some(ZonedDateTime.now().minusHours(1)), Some("http://localhost:9998/feed.xml"))
      (mockSourceDao.getFetchURLs _).expects(*, *).returning(
        fs2.Stream.eval(IO.raiseError[String](new Exception("Mock getFetchURLs failure")))).once()
      (mockSourceDao.getFetchURLs _).expects(*, *).returning(
        fs2.Stream.eval(IO(source1.xmlUrl))).once()
      (mockSourceDao.getFetchURLs _).expects(*, *).returning(
        fs2.Stream.eval(IO.raiseError[String](new Exception("Mock getFetchURLs failure")))).atLeastOnce()
      (mockSourceDao.update _).expects(*, *).returning(IO(true)).atLeastOnce()
      (mockSourceDao.get _).expects(*).returning(IO(Some(source1))).atLeastOnce()

      val updater = new FetchUpdater(mockSourceDao, articleDao, articleContentDao, articleHashDao, articleEmbeddingTaskDao)
      val fetcher = BackgroundFetcher(crawler, mockSourceDao, updater, 1, 1000,
        cleanTimeout = false, pauseSource = false).unsafeRunSync()

      fetcherRunTask = fetcher.run().start
      fetcherRunTask.unsafeRunAndForget()
      Thread.sleep(3000)
    }

    it("should handle update source failure") {
      createValidFeed()
      createValidFeed("/feed2.xml", "test-feed2.xml")

      val mockSourceDao = mock[SourceDao]
      val source1 = Sources.get(Some(ZonedDateTime.now().minusHours(1)), Some("http://localhost:9998/feed.xml"), 1)
      (mockSourceDao.getFetchURLs _).expects(*, *).returning(fs2.Stream.eval(IO(source1.xmlUrl))).once()
      (mockSourceDao.update _).expects(*, *).returning(IO(false)).once()
      (mockSourceDao.getFetchURLs _).expects(*, *).returning(fs2.Stream.eval(IO(source1.xmlUrl))).once()
      (mockSourceDao.update _).expects(*, *).returning(IO(true)).atLeastOnce()
      (mockSourceDao.get _).expects(*).returning(IO(Some(source1))).atLeastOnce()
      (mockSourceDao.getFetchURLs _).expects(*, *).returning(
        fs2.Stream.eval(IO.raiseError(new Exception("Mock getFetchURLs failure")))).atLeastOnce()

      val updater = new FetchUpdater(mockSourceDao, articleDao, articleContentDao, articleHashDao, articleEmbeddingTaskDao)
      val fetcher = BackgroundFetcher(crawler, mockSourceDao, updater, 1, 1000,
        cleanTimeout = false, pauseSource = false).unsafeRunSync()

      fetcherRunTask = fetcher.run().start
      fetcherRunTask.unsafeRunAndForget()
      Thread.sleep(3000)
    }

    it("should handle crawler failure") {
      createValidFeed()
      val source1 = Sources.get(Some(ZonedDateTime.now().minusHours(1)), Some("http://localhost:9998/feed.xml"), 10)
      val mockCrawler = mock[Crawler]
      (mockCrawler.fetch _).expects(*).returning(crawler.fetch("http://localhost:9998/404")).once()
      (mockCrawler.fetch _).expects(*).returning(crawler.fetch("http://localhost:9998/feed.xml")).anyNumberOfTimes()

      sourceDao.insert(source1).unsafeRunSync()

      val updater = new FetchUpdater(sourceDao, articleDao, articleContentDao, articleHashDao, articleEmbeddingTaskDao)
      val fetcher = BackgroundFetcher(mockCrawler, sourceDao, updater, 1, 1000,
        cleanTimeout = false, pauseSource = false).unsafeRunSync()

      fetcherRunTask = fetcher.run().start
      fetcherRunTask.unsafeRunAndForget()
      Thread.sleep(5000)

      sourceDao.get(source1.id).unsafeRunSync().get.title.get shouldBe "CBC | Top Stories News"
    }

    it("should handle failed to mark source as failure") {
      createValidFeed()

      val mockSourceDao = mock[SourceDao]
      val source1 = Sources.get(Some(ZonedDateTime.now().minusHours(1)), Some("http://localhost:9998/feed.xml"))

      val mockCrawler = mock[Crawler]
      (mockCrawler.fetch _).expects(*).returning(crawler.fetch("http://localhost:9998/404")).once()
      (mockCrawler.fetch _).expects(*).returning(crawler.fetch("http://localhost:9998/feed.xml")).anyNumberOfTimes()

      sourceDao.insert(source1).unsafeRunSync()
      (mockSourceDao.getFetchURLs _).expects(*, *)
          .returning(fs2.Stream.eval(IO(source1.xmlUrl)))
          .twice()
      (mockSourceDao.update _)
          .expects(where {(_: ID, updater: SourceUpdater) =>
            updater.fetchFailedMsg.isDefined &&
                updater.fetchFailedMsg.get.isDefined})
          .returning(IO.raiseError(new Exception("Mock update failure exception")))
          .once()
      (mockSourceDao.update _).expects(*, *)
          .onCall{(id: ID, updater: SourceUpdater) => sourceDao.update(id, updater)}
          .anyNumberOfTimes()
      (mockSourceDao.getFetchURLs _).expects(*, *)
          .returning(fs2.Stream.eval(IO.raiseError(new Exception("Mock fetch urls error"))))
          .anyNumberOfTimes()
      (mockSourceDao.get _).expects(*).onCall{id: ID => sourceDao.get(id)}.anyNumberOfTimes()

      val updater = new FetchUpdater(mockSourceDao, articleDao, articleContentDao, articleHashDao, articleEmbeddingTaskDao)
      val fetcher = BackgroundFetcher(mockCrawler, mockSourceDao, updater, 1, 1000,
        cleanTimeout = false, pauseSource = false).unsafeRunSync()

      fetcherRunTask = fetcher.run().start
      fetcherRunTask.unsafeRunAndForget()
      Thread.sleep(3000)

      sourceDao.get(source1.id).unsafeRunSync().get.title.get shouldBe "CBC | Top Stories News"
    }

    it("should handle update article failure") {
      createValidFeed()
      val mockArticleDao = mock[ArticleDao]
      (mockArticleDao.get _).expects(*).returning(IO.pure(None)).anyNumberOfTimes()
      (mockArticleDao.insertOrUpdate _).expects(*).returning(
        IO.raiseError(new Exception("Mock update article failure"))).once()
      (mockArticleDao.insertOrUpdate _).expects(*).returning(IO(true)).atLeastOnce()
      val source1 = Sources.get(Some(ZonedDateTime.now().minusHours(1)), Some("http://localhost:9998/feed.xml"))
      sourceDao.insert(source1).unsafeRunSync()
      val updater = new FetchUpdater(sourceDao, mockArticleDao, articleContentDao, articleHashDao, articleEmbeddingTaskDao)
      val fetcher = BackgroundFetcher(crawler, sourceDao, updater, 1, 1000,
        cleanTimeout = false, pauseSource = false).unsafeRunSync()
      fetcherRunTask = fetcher.run().start
      fetcherRunTask.unsafeRunAndForget()
      Thread.sleep(3000)
    }

    it("should handle update article content failure") {
      createValidFeed()
      val mockArticleContentDao = mock[ArticleContentDao]
      (mockArticleContentDao.insertOrUpdate _).expects(*).returning(
        IO.raiseError(new Exception("Mock update article failure"))).once()
      (mockArticleContentDao.insertOrUpdate _).expects(*).returning(IO(true)).atLeastOnce()
      val source1 = Sources.get(Some(ZonedDateTime.now().minusHours(1)), Some("http://localhost:9998/feed.xml"))
      sourceDao.insert(source1).unsafeRunSync()
      val updater = new FetchUpdater(sourceDao, articleDao, mockArticleContentDao, articleHashDao, articleEmbeddingTaskDao)
      val fetcher = BackgroundFetcher(crawler, sourceDao, updater, 1, 1000,
        cleanTimeout = false, pauseSource = false).unsafeRunSync()
      fetcherRunTask = fetcher.run().start
      fetcherRunTask.unsafeRunAndForget()
      Thread.sleep(3000)
    }

  }

}
