package me.binwang.rss.service

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.sksamuel.elastic4s.ElasticClient
import me.binwang.rss.dao.elasticsearch.{ArticleContentElasticDao, ArticleElasticDao, ArticleSearchElasticDao, ElasticSearchClient}
import me.binwang.rss.dao.hybrid.{ArticleContentHybridDao, ArticleHybridDao}
import me.binwang.rss.dao.redis.{ArticleContentHashCheckDao, ArticleHashCheckDao, RedisCommand}
import me.binwang.rss.dao.sql._
import me.binwang.rss.dao.{ArticleContentDao, ArticleDao}
import me.binwang.rss.generator.ConnectionPoolManager.connectionPool
import me.binwang.rss.generator.{ArticleContents, Articles, Folders}
import me.binwang.rss.llm.{LLMModels, OpenAILLM}
import me.binwang.rss.model._
import me.binwang.rss.util.Throttler
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory
import sttp.client3.http4s.Http4sBackend

import java.time.ZonedDateTime
import java.util.UUID

class ArticleServiceSpec extends AnyFunSpec with BeforeAndAfterEach with BeforeAndAfterAll with Matchers{

  implicit val ioRuntime: IORuntime = IORuntime.global
  implicit val loggerFactory: LoggerFactory[IO] = Slf4jFactory.create[IO]

  private val sttpBackend = Http4sBackend.usingDefaultEmberClientBuilder[IO]().allocated.unsafeRunSync()._1
  private implicit val elasticClient: ElasticClient = ElasticSearchClient()
  private implicit val redisClient: RedisCommand = RedisCommand().allocated.unsafeRunSync()._1
  private implicit val articleSqlDao: ArticleSqlDao = new ArticleSqlDao()
  private implicit val articleElasticDao: ArticleElasticDao = new ArticleElasticDao()
  private implicit val articleHashDao: ArticleHashCheckDao = new ArticleHashCheckDao(redisClient, false)
  private implicit val articleDao: ArticleDao = new ArticleHybridDao(articleSqlDao, articleElasticDao, Some(articleHashDao))

  private val articleContentSqlDao: ArticleContentSqlDao = new ArticleContentSqlDao()
  private val articleContentElasticDao: ArticleContentElasticDao = new ArticleContentElasticDao()
  private val articleContentHashDao: ArticleContentHashCheckDao = new ArticleContentHashCheckDao(redisClient, false)
  private implicit val articleContentDao: ArticleContentDao = new ArticleContentHybridDao(
    articleContentSqlDao, articleContentElasticDao, articleContentHashDao)

  private implicit val articleUserMarkingDao: ArticleUserMarkingSqlDao = new ArticleUserMarkingSqlDao()
  private implicit val userSessionDao: UserSessionSqlDao = new UserSessionSqlDao()
  private implicit val folderDao: FolderSqlDao = new FolderSqlDao()
  private implicit val folderSourceDao: FolderSourceSqlDao = new FolderSourceSqlDao()
  private implicit val userDao: UserSqlDao = new UserSqlDao()
  private val articleSearchDao = new ArticleSearchElasticDao(1.0, 0.0)
  private implicit val authorizer: Authorizer = new Authorizer(Throttler(), userSessionDao, folderDao)
  private val llm = LLMModels(openAI = new OpenAILLM(sttpBackend))
  private val articleService = new ArticleService(articleDao, articleContentDao, articleUserMarkingDao,
    articleSearchDao, userDao, llm, authorizer)

  private val token = UUID.randomUUID().toString
  private val userID = UUID.randomUUID().toString


  override def beforeAll(): Unit = {
    val createTables = for {
      _ <- articleDao.dropTable().flatMap(_ => articleDao.createTable())
      _ <- articleContentDao.dropTable().flatMap(_ => articleContentDao.createTable())
      _ <- userSessionDao.dropTable()
      _ <- userSessionDao.createTable()
      _ <- folderDao.dropTable()
      _ <- folderDao.createTable()
      _ <- folderSourceDao.dropTable()
      _ <- folderSourceDao.createTable()
      _ <- articleUserMarkingDao.dropTable()
      _ <- articleUserMarkingDao.createTable()
      _ <- userDao.dropTable()
      _ <- userDao.createTable()
    } yield ()
    createTables.unsafeRunSync()
  }

  override def beforeEach(): Unit = {
    val clearTables = for {
      _ <- articleDao.deleteAll()
      _ <- articleContentDao.deleteAll()
      _ <- userSessionDao.deleteAll()
      _ <- folderDao.deleteAll()
      _ <- folderSourceDao.deleteAll()
      _ <- articleUserMarkingDao.deleteAll()
      _ <- userDao.deleteAll()
    } yield ()
    clearTables.unsafeRunSync()

    userSessionDao.insert(UserSession(token, userID, ZonedDateTime.now.plusDays(1), isAdmin = false,
      ZonedDateTime.now.plusDays(1))).unsafeRunSync()
  }

  describe("Article Service") {

    it("should get empty article list") {
      articleService.getArticlesBySource(token, "source_id", 10, ZonedDateTime.now(), "")
        .compile.toList.unsafeRunSync().size shouldBe 0
    }

    it("should throw authorization error") {
      assertThrows[UserNotAuthorized] {
        articleService.getArticlesBySource("random_token", "source_id", 10, ZonedDateTime.now(), "")
          .compile.toList.unsafeRunSync()
      }
    }

    it("should get article list by source") {
      val sourceID = SourceID("http://localhost")
      val article1 = Articles.get(Some(sourceID))
      val article2 = Articles.get(Some(sourceID))
      articleDao.insertOrUpdate(article1).unsafeRunSync()
      articleDao.insertOrUpdate(article2).unsafeRunSync()
      val articles = articleService.getArticlesBySource(token, sourceID, 10, ZonedDateTime.now(), "")
        .compile.toList.unsafeRunSync()
      articles.size shouldBe 2
    }

    it("should update article if it exists") {
      val sourceID = SourceID("http://localhost")
      val guid = "test"
      val article1 = Articles.get(Some(sourceID), Some(guid))
      val article2 = article1.copy(title = "new title")
      article1.title should not be article2.title
      articleDao.insertOrUpdate(article1).unsafeRunSync()
      articleDao.get(article1.id).unsafeRunSync().get.title shouldBe article1.title
      articleDao.insertOrUpdate(article2).unsafeRunSync()
      articleDao.get(article1.id).unsafeRunSync().get.title shouldBe article2.title
    }

    it("should update article content if it exists") {
      val sourceID = SourceID("http://localhost")
      val guid = "test"
      val article1 = Articles.get(Some(sourceID), Some(guid))
      val content1 = ArticleContents.get(Some(article1.id))
      val content2 = content1.copy(content = "new content")
      content1 should not be content2
      articleDao.insertOrUpdate(article1).unsafeRunSync()
      articleContentDao.insertOrUpdate(content1).unsafeRunSync()
      articleContentDao.get(content1.id).unsafeRunSync().get shouldBe content1
      articleContentDao.insertOrUpdate(content2).unsafeRunSync()
      articleContentDao.get(content1.id).unsafeRunSync().get shouldBe content2
    }

    it("should get article list by user") {
      val sourceID = SourceID("http://localhost")
      val sourceID2 = SourceID("http://localhost/2")
      val folderID1 = UUID.randomUUID().toString
      val folderID2 = UUID.randomUUID().toString
      folderSourceDao.addSourceToFolder(FolderSourceMapping(
        folderID = folderID1, sourceID = sourceID, userID = userID, position = 0, None)).unsafeRunSync()
      folderSourceDao.addSourceToFolder(FolderSourceMapping(
        folderID = folderID2, sourceID = sourceID2, userID = userID, position = 1, None)).unsafeRunSync()
      val article1 = Articles.get(Some(sourceID))
      val article2 = Articles.get(Some(sourceID2))
      articleDao.insertOrUpdate(article1).unsafeRunSync()
      articleDao.insertOrUpdate(article2).unsafeRunSync()
      val articles = articleService.getMyArticles(token, 10, ZonedDateTime.now(), "")
        .compile.toList.unsafeRunSync()
      articles.size shouldBe 2
    }

    it("should get article list by user with user marking") {
      val sourceID = SourceID("http://localhost")
      val sourceID2 = SourceID("http://localhost/2")
      val folder1 = Folders.get(userID, 1)
      val folder2 = Folders.get(userID, 2)
      val folderID1 = folder1.id
      val folderID2 = folder2.id
      folderDao.insertIfNotExist(folder1).unsafeRunSync()
      folderDao.insertIfNotExist(folder2).unsafeRunSync()
      folderSourceDao.addSourceToFolder(FolderSourceMapping(
        folderID = folderID1, sourceID = sourceID, userID = userID, position = 0, None)).unsafeRunSync()
      folderSourceDao.addSourceToFolder(FolderSourceMapping(
        folderID = folderID2, sourceID = sourceID, userID = userID, position = 0, None)).unsafeRunSync()
      folderSourceDao.addSourceToFolder(FolderSourceMapping(
        folderID = folderID1, sourceID = sourceID2, userID = userID, position = 1, None)).unsafeRunSync()
      folderSourceDao.addSourceToFolder(FolderSourceMapping(
        folderID = folderID2, sourceID = sourceID2, userID = userID, position = 1, None)).unsafeRunSync()
      val article1 = Articles.get(Some(sourceID))
      val article2 = Articles.get(Some(sourceID2))
      articleDao.insertOrUpdate(article1).unsafeRunSync()
      articleDao.insertOrUpdate(article2).unsafeRunSync()
      articleService.readArticle(token, article1.id).unsafeRunSync()
      articleService.readArticle(token, article2.id).unsafeRunSync()
      val articles = articleService.getMyArticlesWithUserMarking(token, 10, ZonedDateTime.now(), "")
        .compile.toList.unsafeRunSync()
      articles.size shouldBe 2
      articles.head.userMarking.read shouldBe true
      articles.head.userMarking.bookmarked shouldBe false
    }

    it("should not get duplicate articles when query by user") {
      val sourceID = SourceID("http://localhost")
      val sourceID2 = SourceID("http://localhost/2")
      val folderID1 = UUID.randomUUID().toString
      val folderID2 = UUID.randomUUID().toString
      folderSourceDao.addSourceToFolder(FolderSourceMapping(
        folderID = folderID1, sourceID = sourceID, userID = userID, position = 0, None)).unsafeRunSync()
      folderSourceDao.addSourceToFolder(FolderSourceMapping(
        folderID = folderID1, sourceID = sourceID, userID = userID, position = 1, None)).unsafeRunSync()
      folderSourceDao.addSourceToFolder(FolderSourceMapping(
        folderID = folderID2, sourceID = sourceID, userID = userID, position = 0, None)).unsafeRunSync()
      folderSourceDao.addSourceToFolder(FolderSourceMapping(
        folderID = folderID2, sourceID = sourceID2, userID = userID, position = 1, None)).unsafeRunSync()
      val article1 = Articles.get(Some(sourceID))
      val article2 = Articles.get(Some(sourceID2))
      articleDao.insertOrUpdate(article1).unsafeRunSync()
      articleDao.insertOrUpdate(article2).unsafeRunSync()
      val articles = articleService.getMyArticles(token, 10, ZonedDateTime.now(), "")
        .compile.toList.unsafeRunSync()
      articles.size shouldBe 2
    }

    it("should get article list by folder with user marking") {
      val sourceID = SourceID("http://localhost")
      val sourceID2 = SourceID("http://localhost/2")
      val folder1 = Folders.get(userID, 1)
      val folder2 = Folders.get(userID, 2)
      val folderID1 = folder1.id
      val folderID2 = folder2.id
      folderDao.insertIfNotExist(folder1).unsafeRunSync()
      folderDao.insertIfNotExist(folder2).unsafeRunSync()
      folderSourceDao.addSourceToFolder(FolderSourceMapping(
        folderID = folderID1, sourceID = sourceID, userID = userID, position = 0, None)).unsafeRunSync()
      folderSourceDao.addSourceToFolder(FolderSourceMapping(
        folderID = folderID2, sourceID = sourceID2, userID = userID, position = 1, None)).unsafeRunSync()
      val article1 = Articles.get(Some(sourceID))
      val article2 = Articles.get(Some(sourceID2))
      articleService.readArticle(token, article1.id).unsafeRunSync()
      articleService.readArticle(token, article2.id).unsafeRunSync()
      articleDao.insertOrUpdate(article1).unsafeRunSync()
      articleDao.insertOrUpdate(article2).unsafeRunSync()
      val articles = articleService.getArticlesByFolderWithUserMarking(token, folderID1, 10, ZonedDateTime.now(), "")
        .compile.toList.unsafeRunSync()
      articles.size shouldBe 1
      articles.head.userMarking.read shouldBe true
    }

    it("should not get full article if both article and content are none") {
      assertThrows[ArticleNotFound] {
        articleService.getFullArticle(token, "article_id").unsafeRunSync()
      }
    }

    it("should not get full article if article is none") {
      val articleID = ID.hash("test id")
      articleContentDao.insertOrUpdate(ArticleContents.get(Some(articleID))).unsafeRunSync()
      assertThrows[ArticleNotFound] {
        articleService.getFullArticle(token, articleID).unsafeRunSync()
      }
    }

    it("should not get full article if content is none") {
      val sourceID = SourceID("http://localhost")
      val guid = "test"
      val articleID = ArticleID(sourceID, guid)
      articleDao.insertOrUpdate(Articles.get(Some(sourceID), Some(guid))).unsafeRunSync()
      assertThrows[ArticleNotFound] {
        articleService.getFullArticle(token, articleID).unsafeRunSync()
      }
    }

    it("should get full article") {
      val sourceID = SourceID("http://localhost")
      val guid = "test"
      val articleID = ArticleID(sourceID, guid)
      val article = Articles.get(Some(sourceID), Some(guid))
      val content = ArticleContents.get(Some(articleID))
      articleDao.insertOrUpdate(article).unsafeRunSync()
      articleContentDao.insertOrUpdate(content).unsafeRunSync()
      val fullArticle = articleService.getFullArticle(token, articleID).unsafeRunSync()
      fullArticle.article shouldBe article
      fullArticle.content shouldBe content.content
    }

    it("should get full article with none user marking") {
      val sourceID = SourceID("http://localhost")
      val guid = "test"
      val articleID = ArticleID(sourceID, guid)
      val article = Articles.get(Some(sourceID), Some(guid))
      val content = ArticleContents.get(Some(articleID))
      articleDao.insertOrUpdate(article).unsafeRunSync()
      articleContentDao.insertOrUpdate(content).unsafeRunSync()
      val fullArticle = articleService.getFullArticleWithUserMarking(token, articleID).unsafeRunSync()
      fullArticle.article.article shouldBe article
      fullArticle.article.content shouldBe content.content
      fullArticle.userMarking.read shouldBe false
      fullArticle.userMarking.bookmarked shouldBe false
    }

    it("should get full article with user marking") {
      val sourceID = SourceID("http://localhost")
      val guid = "test"
      val articleID = ArticleID(sourceID, guid)
      val article = Articles.get(Some(sourceID), Some(guid))
      val content = ArticleContents.get(Some(articleID))
      articleDao.insertOrUpdate(article).unsafeRunSync()
      articleContentDao.insertOrUpdate(content).unsafeRunSync()
      articleService.readArticle(token, articleID).unsafeRunSync()
      val fullArticle = articleService.getFullArticleWithUserMarking(token, articleID).unsafeRunSync()
      fullArticle.article.article shouldBe article
      fullArticle.article.content shouldBe content.content
      fullArticle.userMarking.read shouldBe true
      fullArticle.userMarking.bookmarked shouldBe false
    }

    it("should get article list by source with user mark") {
      val sourceID = SourceID("http://localhost")
      val article1 = Articles.get(Some(sourceID))
      val article2 = Articles.get(Some(sourceID))
      articleDao.insertOrUpdate(article1).unsafeRunSync()
      articleDao.insertOrUpdate(article2).unsafeRunSync()
      val articles = articleService.getArticlesBySourceWithUserMarking(token, sourceID, 10, ZonedDateTime.now(), "")
        .compile.toList.unsafeRunSync()
      articles.size shouldBe 2
      articles.head.userMarking.read shouldBe false
      articles.head.userMarking.bookmarked shouldBe false
    }

    it("should should mark article as read/unread") {
      val sourceID = SourceID("http://localhost")
      val article1 = Articles.get(Some(sourceID))
      articleDao.insertOrUpdate(article1).unsafeRunSync()
      articleService.readArticle(token, article1.id).unsafeRunSync()
      var articles = articleService.getArticlesBySourceWithUserMarking(token, sourceID, 10, ZonedDateTime.now(), "")
        .compile.toList.unsafeRunSync()
      articles.size shouldBe 1
      articles.head.userMarking.read shouldBe true
      articles.head.userMarking.bookmarked shouldBe false

      articleService.readArticle(token, article1.id).unsafeRunSync()
      articles = articleService.getArticlesBySourceWithUserMarking(token, sourceID, 10, ZonedDateTime.now(), "")
        .compile.toList.unsafeRunSync()
      articles.size shouldBe 1
      articles.head.userMarking.read shouldBe true
      articles.head.userMarking.bookmarked shouldBe false

      articleService.unreadArticle(token, article1.id).unsafeRunSync()
      articles = articleService.getArticlesBySourceWithUserMarking(token, sourceID, 10, ZonedDateTime.now(), "")
        .compile.toList.unsafeRunSync()
      articles.size shouldBe 1
      articles.head.userMarking.read shouldBe false
      articles.head.userMarking.bookmarked shouldBe false

      articleService.unreadArticle(token, article1.id).unsafeRunSync()
      articles = articleService.getArticlesBySourceWithUserMarking(token, sourceID, 10, ZonedDateTime.now(), "")
        .compile.toList.unsafeRunSync()
      articles.size shouldBe 1
      articles.head.userMarking.read shouldBe false
      articles.head.userMarking.bookmarked shouldBe false

      articleService.readArticle(token, article1.id).unsafeRunSync()
      articles = articleService.getArticlesBySourceWithUserMarking(token, sourceID, 10, ZonedDateTime.now(), "")
        .compile.toList.unsafeRunSync()
      articles.size shouldBe 1
      articles.head.userMarking.read shouldBe true
      articles.head.userMarking.bookmarked shouldBe false
    }

    it("should should mark article as bookmarked/unbookmarked") {
      val sourceID = SourceID("http://localhost")
      val article1 = Articles.get(Some(sourceID))
      articleDao.insertOrUpdate(article1).unsafeRunSync()
      articleService.bookmarkArticle(token, article1.id).unsafeRunSync()
      var articles = articleService.getArticlesBySourceWithUserMarking(token, sourceID, 10, ZonedDateTime.now(), "")
        .compile.toList.unsafeRunSync()
      articles.size shouldBe 1
      articles.head.userMarking.read shouldBe false
      articles.head.userMarking.bookmarked shouldBe true

      articleService.bookmarkArticle(token, article1.id).unsafeRunSync()
      articles = articleService.getArticlesBySourceWithUserMarking(token, sourceID, 10, ZonedDateTime.now(), "")
        .compile.toList.unsafeRunSync()
      articles.size shouldBe 1
      articles.head.userMarking.read shouldBe false
      articles.head.userMarking.bookmarked shouldBe true

      articleService.unBookmarkArticle(token, article1.id).unsafeRunSync()
      articles = articleService.getArticlesBySourceWithUserMarking(token, sourceID, 10, ZonedDateTime.now(), "")
        .compile.toList.unsafeRunSync()
      articles.size shouldBe 1
      articles.head.userMarking.read shouldBe false
      articles.head.userMarking.bookmarked shouldBe false

      articleService.unBookmarkArticle(token, article1.id).unsafeRunSync()
      articles = articleService.getArticlesBySourceWithUserMarking(token, sourceID, 10, ZonedDateTime.now(), "")
        .compile.toList.unsafeRunSync()
      articles.size shouldBe 1
      articles.head.userMarking.read shouldBe false
      articles.head.userMarking.bookmarked shouldBe false

      articleService.bookmarkArticle(token, article1.id).unsafeRunSync()
      articles = articleService.getArticlesBySourceWithUserMarking(token, sourceID, 10, ZonedDateTime.now(), "")
        .compile.toList.unsafeRunSync()
      articles.size shouldBe 1
      articles.head.userMarking.read shouldBe false
      articles.head.userMarking.bookmarked shouldBe true
    }

    it("should filter articles by bookmarked") {
      val sourceID = SourceID("http://localhost")
      val article1 = Articles.get(Some(sourceID))
      articleDao.insertOrUpdate(article1).unsafeRunSync()

      def testNotBookmarked() = {
        var articles = articleService.getArticlesBySourceWithUserMarking(token, sourceID, 10, ZonedDateTime.now(), "")
          .compile.toList.unsafeRunSync()
        articles.size shouldBe 1
        articles = articleService.getArticlesBySourceWithUserMarking(token, sourceID, 10, ZonedDateTime.now(), "", None, Some(true))
          .compile.toList.unsafeRunSync()
        articles.size shouldBe 0
        articles = articleService.getArticlesBySourceWithUserMarking(token, sourceID, 10, ZonedDateTime.now(), "", None, Some(false))
          .compile.toList.unsafeRunSync()
        articles.size shouldBe 1
      }

      def testBookmarked() = {
        var articles = articleService.getArticlesBySourceWithUserMarking(token, sourceID, 10, ZonedDateTime.now(), "")
          .compile.toList.unsafeRunSync()
        articles.size shouldBe 1
        articles = articleService.getArticlesBySourceWithUserMarking(token, sourceID, 10, ZonedDateTime.now(), "", None, Some(true))
          .compile.toList.unsafeRunSync()
        articles.size shouldBe 1
        articles = articleService.getArticlesBySourceWithUserMarking(token, sourceID, 10, ZonedDateTime.now(), "", None, Some(false))
          .compile.toList.unsafeRunSync()
        articles.size shouldBe 0
      }

      testNotBookmarked()
      articleService.bookmarkArticle(token, article1.id).unsafeRunSync()
      testBookmarked()
      articleService.bookmarkArticle(token, article1.id).unsafeRunSync()
      testBookmarked()
      articleService.unBookmarkArticle(token, article1.id).unsafeRunSync()
      testNotBookmarked()
      articleService.unBookmarkArticle(token, article1.id).unsafeRunSync()
      testNotBookmarked()
    }


    it("should filter articles by deleted") {
      val sourceID = SourceID("http://localhost")
      val article1 = Articles.get(Some(sourceID))
      articleDao.insertOrUpdate(article1).unsafeRunSync()

      def testNotDeleted() = {
        var articles = articleService.getArticlesBySourceWithUserMarking(token, sourceID, 10, ZonedDateTime.now(), "")
          .compile.toList.unsafeRunSync()
        articles.size shouldBe 1
        articles = articleService.getArticlesBySourceWithUserMarking(token, sourceID, 10, ZonedDateTime.now(), "", None, None, Some(true))
          .compile.toList.unsafeRunSync()
        articles.size shouldBe 0
        articles = articleService.getArticlesBySourceWithUserMarking(token, sourceID, 10, ZonedDateTime.now(), "", None, None, Some(false))
          .compile.toList.unsafeRunSync()
        articles.size shouldBe 1
      }

      def testDeleted() = {
        var articles = articleService.getArticlesBySourceWithUserMarking(token, sourceID, 10, ZonedDateTime.now(), "")
          .compile.toList.unsafeRunSync()
        articles.size shouldBe 0
        articles = articleService.getArticlesBySourceWithUserMarking(token, sourceID, 10, ZonedDateTime.now(), "", None, None, Some(true))
          .compile.toList.unsafeRunSync()
        articles.size shouldBe 1
        articles = articleService.getArticlesBySourceWithUserMarking(token, sourceID, 10, ZonedDateTime.now(), "", None, None, Some(false))
          .compile.toList.unsafeRunSync()
        articles.size shouldBe 0
      }

      testNotDeleted()
      articleService.markArticleAsDeleted(token, article1.id).unsafeRunSync()
      testDeleted()
      articleService.markArticleAsDeleted(token, article1.id).unsafeRunSync()
      testDeleted()
      articleService.markArticleAsNotDeleted(token, article1.id).unsafeRunSync()
      testNotDeleted()
      articleService.markArticleAsNotDeleted(token, article1.id).unsafeRunSync()
      testNotDeleted()
    }

    it("should filter articles by read") {
      val sourceID = SourceID("http://localhost")
      val article1 = Articles.get(Some(sourceID))
      articleDao.insertOrUpdate(article1).unsafeRunSync()

      def testNotRead() = {
        var articles = articleService.getArticlesBySourceWithUserMarking(token, sourceID, 10, ZonedDateTime.now(), "")
          .compile.toList.unsafeRunSync()
        articles.size shouldBe 1
        articles = articleService.getArticlesBySourceWithUserMarking(token, sourceID, 10, ZonedDateTime.now(), "", Some(true), None)
          .compile.toList.unsafeRunSync()
        articles.size shouldBe 0
        articles = articleService.getArticlesBySourceWithUserMarking(token, sourceID, 10, ZonedDateTime.now(), "", Some(false), None)
          .compile.toList.unsafeRunSync()
        articles.size shouldBe 1
      }


      def testRead() = {
        var articles = articleService.getArticlesBySourceWithUserMarking(token, sourceID, 10, ZonedDateTime.now(), "")
          .compile.toList.unsafeRunSync()
        articles.size shouldBe 1
        articles = articleService.getArticlesBySourceWithUserMarking(token, sourceID, 10, ZonedDateTime.now(), "", Some(true), None)
          .compile.toList.unsafeRunSync()
        articles.size shouldBe 1
        articles = articleService.getArticlesBySourceWithUserMarking(token, sourceID, 10, ZonedDateTime.now(), "", Some(false), None)
          .compile.toList.unsafeRunSync()
        articles.size shouldBe 0
      }

      testNotRead()
      articleService.readArticle(token, article1.id).unsafeRunSync()
      testRead()
      articleService.readArticle(token, article1.id).unsafeRunSync()
      testRead()
      articleService.unreadArticle(token, article1.id).unsafeRunSync()
      testNotRead()
      articleService.unreadArticle(token, article1.id).unsafeRunSync()
      testNotRead()
    }

    it("should get articles order by score") {
      val sourceID = SourceID("http://localhost")
      val article1 = Articles.get(Some(sourceID)).copy(score = 10)
      val article2 = Articles.get(Some(sourceID)).copy(score = 10)
      val article3 = Articles.get(Some(sourceID)).copy(score = 9)
      articleDao.insertOrUpdate(article1).unsafeRunSync()
      articleDao.insertOrUpdate(article2).unsafeRunSync()
      articleDao.insertOrUpdate(article3).unsafeRunSync()
      val articles = articleService
        .getArticlesBySourceOrderByScoreWithUserMarking(token, sourceID, 1, None, "")
        .compile.toList.unsafeRunSync()
      articles.size shouldBe 1
      articles.head.article.id should not be article3.id
      Seq(articles.head.article.id) should contain oneOf (article1.id, article2.id)
      val articlesPage2 = articleService
        .getArticlesBySourceOrderByScoreWithUserMarking(token, sourceID, 1, Some(articles.head.article.score), articles.head.article.id)
        .compile.toList.unsafeRunSync()
      articlesPage2.size shouldBe 1
      articlesPage2.head.article.id should not be article3.id
      articlesPage2.head.article.id should not be articles.head.article.id
      Seq(articlesPage2.head.article.id) should contain oneOf (article1.id, article2.id)
    }

    // ignore the test since it costs money and API key
    ignore("should get search terms for recommending") {
      val sourceID = SourceID("http://localhost")
      val folder1 = Folders.get(userID, 1)
      val folderID1 = folder1.id
      folderDao.insertIfNotExist(folder1).unsafeRunSync()
      folderSourceDao.addSourceToFolder(FolderSourceMapping(
        folderID = folderID1, sourceID = sourceID, userID = userID, position = 0, None)).unsafeRunSync()
      val articles = Seq(
        "又是一台CD随身听机皇，35年前的机器，看看这样的修复值不值",
        "Wubuntu, the Dubious Linux Windows",
        "Titans Sphere - The Failed 3D Game Controller for PC",
        "Omnibot 2000: The $500 Drink Serving Robot from 1985!",
        "Weird Old Computer Mice: Radios, Moods, & Memory Sticks",
        "LGR Thrifts [Ep.50] Richmond Superstars",
        "$4,750 Laptop From 1997: HP OmniBook 800CT",
        "This 1999 Digital Camera Uses Tiny Clik Disks! Agfa CL30",
      ).map(t => Articles.get(Some(sourceID)).copy(title = t))
      articles.foreach(a => articleDao.insertOrUpdate(a).unsafeRunSync())
      articles.reverse.take(3).foreach(a => articleService.bookmarkArticle(token, a.id).unsafeRunSync())
      val choices = articleService.getFolderRecommendSearchTerms(token, folderID1, 10, ZonedDateTime.now().plusDays(-3), 3).unsafeRunSync()
      println(choices)
      choices.terms.length shouldBe 3
    }

  }
}
