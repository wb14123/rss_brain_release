package me.binwang.rss.cmd

import cats.effect.unsafe.IORuntime
import cats.effect.{IO, Resource}
import me.binwang.rss.dao.redis.{ArticleHashCheckDao, RedisCommand}
import me.binwang.rss.dao.sql._
import me.binwang.rss.generator.ConnectionPoolManager.connectionPool
import me.binwang.rss.generator.Folders
import me.binwang.rss.model.UserSession
import me.binwang.rss.service.{Authorizer, FolderService}
import me.binwang.rss.util.Throttler
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer.startClientAndServer
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

import java.io.InputStream
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.Executors
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future}

class FetchServerSpec extends AnyFunSpec with BeforeAndAfterEach with Matchers with MockFactory {

  implicit val loggerFactory: LoggerFactory[IO] = Slf4jFactory.create[IO]
  implicit val ioRuntime: IORuntime = IORuntime.global
  private implicit val redisClient: RedisCommand = RedisCommand().allocated.unsafeRunSync()._1

  private implicit val folderDao: FolderSqlDao = new FolderSqlDao()
  private val sourceDao = new SourceSqlDao()
  private val folderSourceDao = new FolderSourceSqlDao()
  private val moreLikeThisMappingDao = new MoreLikeThisMappingSqlDao()
  private val articleUserMarkingDao = new ArticleUserMarkingSqlDao()
  private val articleDao = new ArticleSqlDao()
  private val articleHashDao: ArticleHashCheckDao = new ArticleHashCheckDao(redisClient)
  private val articleEmbeddingTaskDao = new ArticleEmbeddingTaskSqlDao()
  private implicit val userSessionDao: UserSessionSqlDao = new UserSessionSqlDao()
  private val authorizer = new Authorizer(Throttler(), userSessionDao, folderDao)

  private val folderService = new FolderService(folderDao, folderSourceDao, sourceDao, authorizer)

  private val testFile = "/opml-mock-test.xml"

  private val token = UUID.randomUUID().toString
  private val userID = UUID.randomUUID().toString

  private def inputStream: Resource[IO, InputStream] = {
    Resource.make {
      IO(getClass.getResourceAsStream(testFile))
    } { i =>
      IO(i.close())
    }
  }

  override def beforeEach(): Unit = {
    folderDao.dropTable().unsafeRunSync()
    sourceDao.dropTable().unsafeRunSync()
    folderSourceDao.dropTable().unsafeRunSync()
    articleDao.dropTable().unsafeRunSync()
    userSessionDao.dropTable().unsafeRunSync()
    moreLikeThisMappingDao.dropTable().unsafeRunSync()
    articleUserMarkingDao.dropTable().unsafeRunSync()
    articleHashDao.dropTable().unsafeRunSync()
    articleEmbeddingTaskDao.dropTable().unsafeRunSync()

    folderDao.createTable().unsafeRunSync()
    sourceDao.createTable().unsafeRunSync()
    folderSourceDao.createTable().unsafeRunSync()
    articleDao.createTable().unsafeRunSync()
    userSessionDao.createTable().unsafeRunSync()
    moreLikeThisMappingDao.createTable().unsafeRunSync()
    articleUserMarkingDao.createTable().unsafeRunSync()
    articleEmbeddingTaskDao.createTable().unsafeRunSync()

    userSessionDao.insert(UserSession(token, userID, ZonedDateTime.now.plusDays(1), isAdmin = false,
      ZonedDateTime.now.plusDays(7))).unsafeRunSync()
  }


  describe("Fetch Server") {

    /*
    Run it separately from other tests since it's hard to stop the processes of FetchServer
     */
    ignore("should import OPML file") {
      val folder = Folders.get(userID, 0, isUserDefault = true)
      folderDao.insertIfNotExist(folder).unsafeRunSync()

      folderService.importFromOPML(token, inputStream).unsafeRunSync()

      val mockServer: ClientAndServer = startClientAndServer(9998)

      def createValidFeed(path: String, file: String): Unit = {
        mockServer.when(request().withPath(path)).respond(
          response()
              .withStatusCode(200)
              .withHeader("Content-Type", "text/html; charset=utf-8")
              .withBody(scala.io.Source.fromResource(file).mkString)
        )
      }

      createValidFeed("/feed1.xml", "test-feed.xml")
      createValidFeed("/feed2.xml", "test-feed2.xml")

      val shutdown = IO.blocking(FetchServer.main(Array())).start.unsafeRunCancelable()

      Thread.sleep(10000)

      val articles = articleDao.listByUser(userID, 1000, ZonedDateTime.now()).compile.toList.unsafeRunSync()
      articles should have length 30

      val result = shutdown()
      Await.result(result, Duration.Inf)
      mockServer.stop()
      Thread.sleep(2000)
    }

  }
}
