package me.binwang.rss.fetch.fetcher

import cats.effect.{FiberIO, IO}
import cats.effect.unsafe.IORuntime
import com.sksamuel.elastic4s.ElasticClient
import me.binwang.rss.dao.ArticleDao
import me.binwang.rss.dao.elasticsearch.{ArticleElasticDao, ArticleSearchElasticDao, ElasticSearchClient}
import me.binwang.rss.dao.hybrid.ArticleHybridDao
import me.binwang.rss.dao.redis.{ArticleHashCheckDao, RedisCommand}
import me.binwang.rss.dao.sql.{ArticleEmbeddingTaskSqlDao, ArticleSqlDao, FolderSourceSqlDao}
import me.binwang.rss.generator.{Articles, MockSentenceTransformServer}
import me.binwang.rss.generator.ConnectionPoolManager.connectionPool
import me.binwang.rss.model.{ArticleEmbeddingTask, EmbeddingUpdateStatus}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

import java.time.ZonedDateTime
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

class ArticleEmbeddingWorkerSpec extends AnyFunSpec with BeforeAndAfterEach with BeforeAndAfterAll with Matchers{

  implicit val ioRuntime: IORuntime = IORuntime.global
  implicit val loggerFactory: LoggerFactory[IO] = Slf4jFactory.create[IO]

  private val grpcPort = 9999
  private val mockEmbeddings = Seq.fill(ArticleElasticDao.titleEmbeddingVectorSize)(0.0)
  private val mockGrpcServer = new MockSentenceTransformServer(grpcPort, _ => mockEmbeddings)
  private var mockGrpcServerShutdown: Option[() => Future[Unit]] = None

  private implicit val elasticClient: ElasticClient = ElasticSearchClient()
  private implicit val redisClient: RedisCommand = RedisCommand().allocated.unsafeRunSync()._1
  private implicit val folderSourceDao: FolderSourceSqlDao = new FolderSourceSqlDao()
  private implicit val articleSqlDao: ArticleSqlDao = new ArticleSqlDao()
  private implicit val articleElasticDao: ArticleElasticDao = new ArticleElasticDao()
  private implicit val articleHashDao: ArticleHashCheckDao = new ArticleHashCheckDao(redisClient, false)
  private implicit val articleDao: ArticleDao = new ArticleHybridDao(articleSqlDao, articleElasticDao, Some(articleHashDao))
  private val articleEmbeddingTaskDao = new ArticleEmbeddingTaskSqlDao()
  private val articleSearchDao = new ArticleSearchElasticDao(1.0, 0.0)

  private val worker = ArticleEmbeddingWorker(articleEmbeddingTaskDao, articleSearchDao, 100, 600 * 1000,
    600 * 1000, 10, 2, "127.0.0.1", grpcPort).allocated.unsafeRunSync()._1
  private var workerFiber: Option[FiberIO[Unit]] = None


  override def beforeAll(): Unit = {
    mockGrpcServerShutdown = Some(mockGrpcServer.run().allocated.unsafeRunCancelable())

    (folderSourceDao.dropTable()
      >> folderSourceDao.createTable()
      >> articleEmbeddingTaskDao.dropTable()
      >> articleEmbeddingTaskDao.createTable()
      >> articleSearchDao.dropTable()
      >> articleSearchDao.createTable()
      >> articleDao.dropTable()
      >> articleDao.createTable()
    ).unsafeRunSync()
  }

  override def afterAll(): Unit = {
    mockGrpcServerShutdown.foreach(func => {
      val result = func()
      Await.result(result, Duration.Inf)
    })
    workerFiber.foreach(fiber => {
      (fiber.cancel >> fiber.join).unsafeRunSync()
    })
  }

  override def beforeEach(): Unit = {
    (folderSourceDao.deleteAll()
      >> articleEmbeddingTaskDao.deleteAll()
      >> articleSearchDao.deleteAll()
      >> articleDao.deleteAll()
    ).unsafeRunSync()
  }

  describe("article embedding worker") {

    it("should get embeddings") {
      workerFiber = Some(worker.run().start.unsafeRunSync())
      val article = Articles.get()
      val articleID = article.id
      val title = "random title"
      articleDao.insertOrUpdate(article).unsafeRunSync()
      articleEmbeddingTaskDao.schedule(ArticleEmbeddingTask(
        articleID = articleID,
        title = title,
        scheduledAt = ZonedDateTime.now()
      )).unsafeRunSync()

      // wait for task to finish
      Thread.sleep(5000)

      // task should be finished
      val task = articleEmbeddingTaskDao.get(articleID).unsafeRunSync().get
      task.status shouldBe EmbeddingUpdateStatus.FINISHED

      val embedding = articleSearchDao.getTitleEmbedding(articleID).unsafeRunSync().get
      embedding shouldBe mockEmbeddings
    }

  }

}
