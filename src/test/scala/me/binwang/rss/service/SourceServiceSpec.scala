package me.binwang.rss.service

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import me.binwang.rss.dao.redis.{ArticleHashCheckDao, RedisCommand}
import me.binwang.rss.dao.sql._
import me.binwang.rss.fetch.crawler.HttpCrawler
import me.binwang.rss.fetch.fetcher.{BackgroundFetcher, FetchUpdater}
import me.binwang.rss.generator.ConnectionPoolManager.connectionPool
import me.binwang.rss.generator.Folders
import me.binwang.rss.mail.MailSender
import me.binwang.rss.model._
import me.binwang.rss.sourcefinder.MultiSourceFinder
import me.binwang.rss.util.Throttler
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

class SourceServiceSpec extends AnyFunSpec with BeforeAndAfterEach with BeforeAndAfterAll with Matchers with MockFactory {

  implicit val ioRuntime: IORuntime = IORuntime.global

  implicit val loggerFactory: LoggerFactory[IO] = Slf4jFactory.create[IO]
  private val redisClient: RedisCommand = RedisCommand().allocated.unsafeRunSync()._1
  private implicit val articleDao: ArticleSqlDao = new ArticleSqlDao()
  private implicit val userDao: UserSqlDao = new UserSqlDao()
  private implicit val articleContentDao: ArticleContentSqlDao = new ArticleContentSqlDao()
  private implicit val userSessionDao: UserSessionSqlDao = new UserSessionSqlDao()
  private implicit val sourceDao: SourceSqlDao = new SourceSqlDao()
  private implicit val folderDao: FolderSqlDao = new FolderSqlDao()
  private implicit val folderSourceDao: FolderSourceSqlDao = new FolderSourceSqlDao()
  private implicit val moreLikeThisMappingDao: MoreLikeThisMappingSqlDao= new MoreLikeThisMappingSqlDao()
  private implicit val redditSessionDao: RedditSessionSqlDao = new RedditSessionSqlDao()
  private implicit val passwordResetDao: PasswordResetSqlDao = new PasswordResetSqlDao()
  private implicit val paymentCustomerDao: PaymentCustomerSqlDao = new PaymentCustomerSqlDao()
  private val articleEmbeddingTaskDao = new ArticleEmbeddingTaskSqlDao()
  private val articleHashDao: ArticleHashCheckDao = new ArticleHashCheckDao(redisClient, false)
  private val crawler = HttpCrawler().allocated.unsafeRunSync()._1
  private val updater = new FetchUpdater(sourceDao, articleDao, articleContentDao, articleHashDao, articleEmbeddingTaskDao)
  private implicit val fetcher: BackgroundFetcher = BackgroundFetcher(crawler, sourceDao, updater, 10).unsafeRunSync()
  private implicit val authorizer: Authorizer = new Authorizer(Throttler(), userSessionDao, folderDao)
  private val sourceFinder = new MultiSourceFinder(Seq())
  private val mailSender = mock[MailSender]

  private val sourceService= new SourceService(sourceDao, folderSourceDao, folderDao, fetcher, authorizer, sourceFinder)
  private val userService = new UserService(userDao, userSessionDao, null, null, null, null, folderDao,
    redditSessionDao, passwordResetDao, paymentCustomerDao, mailSender, authorizer, validateEmail = false)
  private val moreLikeThisService = new MoreLikeThisService(moreLikeThisMappingDao, authorizer)

  private var token: String = _
  private var userID: String = _
  private var defaultFolderID: String = _
  private var mockServer: ClientAndServer = _
  private val url = "http://localhost:9998/feed.xml"
  private val url2 = "http://localhost:9998/new.xml"
  private val url3 = "http://localhost:9998/feed3.xml"
  private val errorUrl = "http://localhost:9998/404"

  private val username = "username"
  private val password = "password"
  private val email = "abc@example.com"

  override def beforeAll(): Unit = {
    val createTables = for {
      _ <- articleDao.dropTable().flatMap(_ => articleDao.createTable())
      _ <- articleContentDao.dropTable().flatMap(_ => articleContentDao.createTable())
      _ <- userSessionDao.dropTable()
      _ <- userSessionDao.createTable()
      _ <- folderDao.dropTable()
      _ <- folderDao.createTable()
      _ <- sourceDao.dropTable()
      _ <- sourceDao.createTable()
      _ <- passwordResetDao.dropTable()
      _ <- passwordResetDao.createTable()
      _ <- userDao.dropTable()
      _ <- userDao.createTable()
      _ <- folderSourceDao.dropTable()
      _ <- folderSourceDao.createTable()
      _ <- redditSessionDao.dropTable()
      _ <- redditSessionDao.createTable()
      _ <- paymentCustomerDao.dropTable()
      _ <- paymentCustomerDao.createTable()
      _ <- moreLikeThisMappingDao.dropTable()
      _ <- moreLikeThisMappingDao.createTable()
      _ <- articleHashDao.dropTable()
      _ <- articleEmbeddingTaskDao.dropTable()
      _ <- articleEmbeddingTaskDao.createTable()
    } yield ()
    createTables.unsafeRunSync()
  }

  override def beforeEach(): Unit = {
    val clearTables = for {
      _ <- articleDao.deleteAll()
      _ <- articleContentDao.deleteAll()
      _ <- userSessionDao.deleteAll()
      _ <- folderDao.deleteAll()
      _ <- sourceDao.deleteAll()
      _ <- passwordResetDao.deleteAll()
      _ <- userDao.deleteAll()
      _ <- folderSourceDao.deleteAll()
      _ <- redditSessionDao.deleteAll()
      _ <- paymentCustomerDao.deleteAll()
      _ <- moreLikeThisMappingDao.deleteAll()
      _ <- articleHashDao.dropTable()
      _ <- articleEmbeddingTaskDao.deleteAll()
    } yield ()
    clearTables.unsafeRunSync()

    val userInfo = userService.signUp(username, password, email).unsafeRunSync()
    userID = userInfo.id
    defaultFolderID = userInfo.defaultFolderID
    token = userService.login(email, password).unsafeRunSync().token

    mockServer = startClientAndServer(9998)

    val testFeedResponse = response()
      .withStatusCode(200)
      .withHeader("Content-Type", "text/html; charset=utf-8")
      .withBody(scala.io.Source.fromResource("test-feed.xml").mkString)

    mockServer.when(request().withPath("/feed.xml")).respond(testFeedResponse)
    mockServer.when(request().withPath("/new.xml")).respond(testFeedResponse)
    mockServer.when(request().withPath("/feed3.xml")).respond(testFeedResponse)

    mockServer.when(request().withPath("/404")).respond(
      response("404 not found").withStatusCode(404))
  }

  override def afterEach(): Unit = {
    mockServer.stop()
  }

  describe("Source Service") {

    it("should import source") {
      val sourceID = SourceID(url)
      sourceService.importSource(token, url).unsafeRunSync() shouldBe sourceID
      sourceDao.get(sourceID).unsafeRunSync().isDefined shouldBe true
    }

    it("should get sources by folders") {
      val sourceID = SourceID(url)
      sourceService.importSource(token, url).unsafeRunSync() shouldBe sourceID
      sourceDao.get(sourceID).unsafeRunSync().isDefined shouldBe true
      sourceService.addSourceToFolder(token, defaultFolderID, sourceID, 0).unsafeRunSync()
      val sources = sourceService.getSourcesInFolders(token, Seq(defaultFolderID), 20).compile.toList.unsafeRunSync()
      sources.length shouldBe 1
    }

    it("should throw fetch error") {
      val sourceID = SourceID(errorUrl)
      assertThrows[FetchSourceError] {
        sourceService.importSource(token, errorUrl).unsafeRunSync() shouldBe sourceID
      }
      sourceDao.get(sourceID).unsafeRunSync() shouldBe None
    }

    it("should add source to user default folder and get it") {
      val sourceID = SourceID(url)
      sourceService.importSource(token, url).unsafeRunSync() shouldBe sourceID
      sourceService.addSourceToFolder(token, defaultFolderID, sourceID, 0).unsafeRunSync()
      val sources = sourceService.getMySourcesWithFolders(token, 1000, 0).compile.toList.unsafeRunSync()
      sources.length shouldBe 1
      val source = sources.head
      source.folderMapping.folderID shouldBe defaultFolderID
      source.folderMapping.customSourceName shouldBe None
      source.folderMapping.showTitle shouldBe true
      source.folderMapping.showMedia shouldBe false
      source.folderMapping.showFullArticle shouldBe false
      source.source.id shouldBe sourceID
      sourceService.getSource(token, sourceID).unsafeRunSync() shouldBe source.source
    }

    it("should replace source with new instance") {
      val sourceID = SourceID(url)
      sourceService.importSource(token, url).unsafeRunSync() shouldBe sourceID
      sourceService.addSourceToFolder(token, defaultFolderID, sourceID, 0).unsafeRunSync()
      var sources = sourceService.getMySourcesWithFolders(token, 1000, 0).compile.toList.unsafeRunSync()
      sources.length shouldBe 1
      sources.head.source.xmlUrl shouldBe url
      sourceService.replaceSourceInstance(token, "http://localhost:9998/feed", "http://localhost:9998/new", 10)
        .unsafeRunSync() shouldBe 1
      sources = sourceService.getMySourcesWithFolders(token, 1000, 0).compile.toList.unsafeRunSync()
      sources.length shouldBe 1
      sources.head.source.xmlUrl shouldBe "http://localhost:9998/new.xml"

      // add old instance back and try again
      sourceService.addSourceToFolder(token, defaultFolderID, sourceID, 0).unsafeRunSync()
      sources = sourceService.getMySourcesWithFolders(token, 1000, 0).compile.toList.unsafeRunSync()
      sources.length shouldBe 2
      sourceService.replaceSourceInstance(token, "http://localhost:9998/feed", "http://localhost:9998/new", 10)
        .unsafeRunSync() shouldBe 1
      sources = sourceService.getMySourcesWithFolders(token, 1000, 0).compile.toList.unsafeRunSync()
      sources.length shouldBe 1
      sources.head.source.xmlUrl shouldBe "http://localhost:9998/new.xml"
    }

    it("should not include in count when replace instance failed") {
      val sourceID = SourceID(url)
      sourceService.importSource(token, url).unsafeRunSync() shouldBe sourceID
      sourceService.addSourceToFolder(token, defaultFolderID, sourceID, 0).unsafeRunSync()
      val sources = sourceService.getMySourcesWithFolders(token, 1000, 0).compile.toList.unsafeRunSync()
      sources.length shouldBe 1
      sources.head.source.xmlUrl shouldBe url
      sourceService.replaceSourceInstance(token, "http://localhost:9998/feed", "http://localhost:9998/404", 10)
        .unsafeRunSync() shouldBe 0
    }

    it("should import source if it is not already in database") {
      val sourceID = SourceID(url)
      sourceService.getOrImportSource(token, url).unsafeRunSync().id shouldBe sourceID
      sourceService.getSource(token, sourceID).unsafeRunSync().id shouldBe sourceID
    }

    it("should get source if it is already in database") {
      val sourceID = SourceID(url)
      sourceService.getOrImportSource(token, url).unsafeRunSync().id shouldBe sourceID
      articleDao.listBySource(sourceID, 10, ZonedDateTime.now()).compile.toList.unsafeRunSync().isEmpty shouldBe false

      // cleanup article data
      articleDao.dropTable().unsafeRunSync()
      articleDao.createTable().unsafeRunSync()
      articleDao.listBySource(sourceID, 10, ZonedDateTime.now()).compile.toList.unsafeRunSync().isEmpty shouldBe true

      sourceService.getOrImportSource(token, url).unsafeRunSync().id shouldBe sourceID

      // shouldn't be updated if the source is not paused
      articleDao.listBySource(sourceID, 10, ZonedDateTime.now()).compile.toList.unsafeRunSync().isEmpty shouldBe true
    }

    it("should update source if it has been paused for a while") {
      val sourceID = SourceID(url)
      sourceService.getOrImportSource(token, url).unsafeRunSync().id shouldBe sourceID

      articleDao.listBySource(sourceID, 10, ZonedDateTime.now()).compile.toList.unsafeRunSync().isEmpty shouldBe false

      // cleanup article data
      articleDao.dropTable().unsafeRunSync()
      articleHashDao.dropTable().unsafeRunSync()
      articleDao.createTable().unsafeRunSync()
      articleDao.listBySource(sourceID, 10, ZonedDateTime.now()).compile.toList.unsafeRunSync().isEmpty shouldBe true

      // pause source and update the last fetch time to 3 days ago
      sourceDao.update(sourceID, SourceUpdater(
        fetchStatus = Some(FetchStatus.PAUSED),
        fetchCompletedAt = Some(Some(ZonedDateTime.now().plusDays(-3))))).unsafeRunSync()

      // source should be updated since it's paused and last fetch is more than 1 day ago
      sourceService.getOrImportSource(token, url).unsafeRunSync().id shouldBe sourceID
      articleDao.listBySource(sourceID, 10, ZonedDateTime.now()).compile.toList.unsafeRunSync().isEmpty shouldBe false
    }

    it("should throw error if source already in folder") {
      val sourceID = SourceID(url)
      sourceService.importSource(token, url).unsafeRunSync() shouldBe sourceID
      sourceService.addSourceToFolder(token, defaultFolderID, sourceID, 0).unsafeRunSync()
      assertThrows[SourceAlreadyInFolder] {
        sourceService.addSourceToFolder(token, defaultFolderID, sourceID, 0).unsafeRunSync()
      }
    }

    it("should copy display property while add source") {
      val sourceID = SourceID(url)
      sourceService.importSource(token, url).unsafeRunSync() shouldBe sourceID
      sourceDao.update(sourceID, SourceUpdater(
        showTitle = Some(false), showMedia = Some(true), showFullArticle = Some(true))).unsafeRunSync()
      sourceService.addSourceToFolder(token, defaultFolderID, sourceID, 0).unsafeRunSync()
      val sources = sourceService.getMySourcesWithFolders(token, 1000, 0).compile.toList.unsafeRunSync()
      sources.length shouldBe 1
      val source = sources.head
      source.folderMapping.folderID shouldBe defaultFolderID
      source.folderMapping.customSourceName shouldBe None
      source.folderMapping.showTitle shouldBe false
      source.folderMapping.showMedia shouldBe true
      source.folderMapping.showFullArticle shouldBe true
    }

    it("should copy display property from another mapping") {
      val sourceID = SourceID(url)
      val folder = Folders.get(userID, 1000)
      folderDao.insertIfNotExist(folder).unsafeRunSync()
      sourceService.importSource(token, url).unsafeRunSync() shouldBe sourceID
      sourceService.addSourceToFolder(token, defaultFolderID, sourceID, 0).unsafeRunSync()
      sourceService.updateSourceMapping(token, sourceID, FolderSourceMappingUpdater(showTitle = Some(false))).unsafeRunSync()
      sourceService.addSourceToFolder(token, folder.id, sourceID, 0).unsafeRunSync()
      val sources = sourceService.getSourcesInFolder(token, folder.id, 1000, -1).compile.toList.unsafeRunSync()
      sources.length shouldBe 1
      val source = sources.head
      source.folderMapping.folderID shouldBe folder.id
      source.folderMapping.customSourceName shouldBe None
      source.folderMapping.showTitle shouldBe false
      source.folderMapping.showMedia shouldBe false
      source.folderMapping.showFullArticle shouldBe false
    }

    it("should throw error if no source is found") {
      val sourceID = SourceID(url)
      assertThrows[SourceNotFound] {
        sourceService.getSource(token, sourceID).unsafeRunSync()
      }
    }

    it("should throw error if add to folder with a not found source") {
      val sourceID = SourceID(url)
      assertThrows[SourceNotFound] {
        sourceService.addSourceToFolder(token, defaultFolderID, sourceID, 0).unsafeRunSync()
      }
    }

    it("should delete source from folder") {
      val sourceID = SourceID(url)
      sourceService.importSource(token, url).unsafeRunSync() shouldBe sourceID
      sourceService.addSourceToFolder(token, defaultFolderID, sourceID, 0).unsafeRunSync()
      val sources = sourceService.getMySourcesWithFolders(token, 1000, 0).compile.toList.unsafeRunSync()
      sources.length shouldBe 1
      sourceService.delSourceFromFolder(token, defaultFolderID, sourceID).unsafeRunSync() shouldBe true
      sourceService.getMySourcesWithFolders(token, 1000, 0).compile.toList.unsafeRunSync().length shouldBe 0
      sourceService.delSourceFromFolder(token, defaultFolderID, sourceID).unsafeRunSync() shouldBe false
    }

    it("should delete source for user") {
      val sourceID = SourceID(url)
      sourceService.importSource(token, url).unsafeRunSync() shouldBe sourceID
      sourceService.addSourceToFolder(token, defaultFolderID, sourceID, 0).unsafeRunSync()
      val sources = sourceService.getMySourcesWithFolders(token, 1000, 0).compile.toList.unsafeRunSync()
      sources.length shouldBe 1
      sourceService.delSourceForUser(token, sourceID).unsafeRunSync()
      sourceService.getMySourcesWithFolders(token, 1000, 0).compile.toList.unsafeRunSync().length shouldBe 0
      sourceService.delSourceFromFolder(token, defaultFolderID, sourceID).unsafeRunSync() shouldBe false
    }

    it("should unpause source if add source to folder again") {
      val sourceID = SourceID(url)
      sourceService.importSource(token, url).unsafeRunSync() shouldBe sourceID
      sourceService.addSourceToFolder(token, defaultFolderID, sourceID, 0).unsafeRunSync()

      sourceService.getSource(token, sourceID).unsafeRunSync().fetchStatus shouldBe FetchStatus.SCHEDULED
      sourceService.delSourceForUser(token, sourceID).unsafeRunSync()
      sourceDao.pauseNotInFolderSources().unsafeRunSync()
      sourceDao.get(sourceID).unsafeRunSync().get.fetchStatus shouldBe FetchStatus.PAUSED

      sourceService.addSourceToFolder(token, defaultFolderID, sourceID, 0).unsafeRunSync()
      sourceService.getSource(token, sourceID).unsafeRunSync().fetchStatus shouldBe FetchStatus.SCHEDULED
    }

    it("should add and get more like this mappings") {
      val sourceID = SourceID(url)
      val sourceID2 = SourceID("http://testurl.com")
      val folderID = userService.getMyUserInfo(token).unsafeRunSync().defaultFolderID

      moreLikeThisService.addMoreLikeThisMapping(
        token, sourceID, MoreLikeThisType.SOURCE, sourceID2, MoreLikeThisType.SOURCE, 1).unsafeRunSync()
      var mappings = moreLikeThisService.getMoreLikeThisMappings(token, sourceID, MoreLikeThisType.SOURCE, 10, 0)
        .compile.toList.unsafeRunSync()
      mappings.length shouldBe 1
      mappings.head.moreLikeThisID shouldBe sourceID2
      mappings.head.moreLikeThisType shouldBe MoreLikeThisType.SOURCE

      moreLikeThisService.addMoreLikeThisMapping(
        token, sourceID, MoreLikeThisType.SOURCE, folderID, MoreLikeThisType.FOLDER, 2).unsafeRunSync()
      mappings = moreLikeThisService.getMoreLikeThisMappings(token, sourceID, MoreLikeThisType.SOURCE, 10, 0)
        .compile.toList.unsafeRunSync()
      mappings.length shouldBe 2
      mappings.head.moreLikeThisID shouldBe sourceID2
      mappings.head.moreLikeThisType shouldBe MoreLikeThisType.SOURCE
      mappings(1).moreLikeThisID shouldBe folderID
      mappings(1).moreLikeThisType shouldBe MoreLikeThisType.FOLDER

      moreLikeThisService.updateMoreLikeThisMapping(
        token, sourceID, MoreLikeThisType.SOURCE, sourceID2, MoreLikeThisType.SOURCE, 1000).unsafeRunSync()
      mappings = moreLikeThisService.getMoreLikeThisMappings(token, sourceID, MoreLikeThisType.SOURCE, 10, 0)
        .compile.toList.unsafeRunSync()
      mappings.length shouldBe 2
      mappings.head.moreLikeThisID shouldBe folderID
      mappings(1).moreLikeThisID shouldBe sourceID2
      mappings(1).position shouldBe 1000

      moreLikeThisService.delMoreLikeThisMapping(
        token, sourceID, MoreLikeThisType.SOURCE, sourceID2, MoreLikeThisType.SOURCE).unsafeRunSync()
      mappings = moreLikeThisService.getMoreLikeThisMappings(token, sourceID, MoreLikeThisType.SOURCE, 10, 0)
        .compile.toList.unsafeRunSync()
      mappings.length shouldBe 1
      mappings.head.moreLikeThisID shouldBe folderID
      mappings.head.moreLikeThisType shouldBe MoreLikeThisType.FOLDER

      moreLikeThisService.delMoreLikeThisMapping(
        token, sourceID, MoreLikeThisType.SOURCE, folderID, MoreLikeThisType.FOLDER).unsafeRunSync()
      mappings = moreLikeThisService.getMoreLikeThisMappings(token, sourceID, MoreLikeThisType.SOURCE, 10, 0)
        .compile.toList.unsafeRunSync()
      mappings.length shouldBe 0
    }

    it("should cleanup more like this mapping order") {
      val sourceID = SourceID(url)
      val fromID2 = SourceID("http://testurl2.com")
      val sourceID2 = SourceID("http://testurl.com")
      val folderID = userService.getMyUserInfo(token).unsafeRunSync().defaultFolderID

      moreLikeThisService.addMoreLikeThisMapping(
        token, sourceID, MoreLikeThisType.SOURCE, sourceID2, MoreLikeThisType.SOURCE, 1).unsafeRunSync()
      moreLikeThisService.addMoreLikeThisMapping(
        token, sourceID, MoreLikeThisType.SOURCE, folderID, MoreLikeThisType.FOLDER, 2).unsafeRunSync()
      moreLikeThisService.addMoreLikeThisMapping(
        token, fromID2 , MoreLikeThisType.SOURCE, sourceID2, MoreLikeThisType.SOURCE, 1).unsafeRunSync()
      moreLikeThisService.addMoreLikeThisMapping(
        token, fromID2, MoreLikeThisType.SOURCE, folderID, MoreLikeThisType.FOLDER, 2).unsafeRunSync()
      var mappings = moreLikeThisService.getMoreLikeThisMappings(token, sourceID, MoreLikeThisType.SOURCE, 10, 0)
        .compile.toList.unsafeRunSync()
      mappings.length shouldBe 2
      mappings.head.position shouldBe 1
      mappings(1).position shouldBe 2

      mappings = moreLikeThisService.getMoreLikeThisMappings(token, fromID2, MoreLikeThisType.SOURCE, 10, 0)
        .compile.toList.unsafeRunSync()
      mappings.length shouldBe 2
      mappings.head.position shouldBe 1
      mappings(1).position shouldBe 2

      moreLikeThisService.cleanupMappingsPosition(token, sourceID, MoreLikeThisType.SOURCE).unsafeRunSync()
      mappings = moreLikeThisService.getMoreLikeThisMappings(token, sourceID, MoreLikeThisType.SOURCE, 10, 0)
        .compile.toList.unsafeRunSync()
      mappings.length shouldBe 2
      mappings.head.position shouldBe 1000
      mappings(1).position shouldBe 2000

      // make sure order in other mappings don't change
      mappings = moreLikeThisService.getMoreLikeThisMappings(token, fromID2, MoreLikeThisType.SOURCE, 10, 0)
        .compile.toList.unsafeRunSync()
      mappings.length shouldBe 2
      mappings.head.position shouldBe 1
      mappings(1).position shouldBe 2
    }

    it("should add and get more like this mappings for all subscriptions") {
      val sourceID = SourceID(url)

      moreLikeThisService.addMoreLikeThisMapping(
        token, sourceID, MoreLikeThisType.SOURCE, "", MoreLikeThisType.ALL, 1).unsafeRunSync()
      var mappings = moreLikeThisService.getMoreLikeThisMappings(token, sourceID, MoreLikeThisType.SOURCE, 10, 0)
        .compile.toList.unsafeRunSync()
      mappings.length shouldBe 1
      mappings.head.moreLikeThisID shouldBe ""
      mappings.head.moreLikeThisType shouldBe MoreLikeThisType.ALL

      moreLikeThisService.delMoreLikeThisMapping(
        token, sourceID, MoreLikeThisType.SOURCE, "", MoreLikeThisType.ALL).unsafeRunSync()
      mappings = moreLikeThisService.getMoreLikeThisMappings(token, sourceID, MoreLikeThisType.SOURCE, 10, 0)
        .compile.toList.unsafeRunSync()
      mappings.length shouldBe 0
    }

    it("should move source after another source if the target source is the last one") {
      val sourceID = SourceID(url)
      val sourceID2 = SourceID(url2)
      sourceService.importSource(token, url).unsafeRunSync() shouldBe sourceID
      sourceService.importSource(token, url2).unsafeRunSync() shouldBe sourceID2
      sourceDao.get(sourceID).unsafeRunSync().isDefined shouldBe true
      sourceDao.get(sourceID2).unsafeRunSync().isDefined shouldBe true
      sourceService.addSourceToFolder(token, defaultFolderID, sourceID, 1000).unsafeRunSync()
      sourceService.addSourceToFolder(token, defaultFolderID, sourceID2, 2000).unsafeRunSync()
      var sources = sourceService.getSourcesInFolders(token, Seq(defaultFolderID), 20).compile.toList.unsafeRunSync()
      sources.length shouldBe 2
      sources.head.source.id shouldBe sourceID
      sources.head.folderMapping.position shouldBe 1000
      sources(1).source.id shouldBe sourceID2
      sources(1).folderMapping.position shouldBe 2000

      // move source after
      sourceService.moveSourceAfter(token, defaultFolderID, sourceID, sourceID2).unsafeRunSync()
      sources = sourceService.getSourcesInFolders(token, Seq(defaultFolderID), 20).compile.toList.unsafeRunSync()
      sources.length shouldBe 2
      sources.head.source.id shouldBe sourceID2
      sources.head.folderMapping.position shouldBe 2000
      sources(1).source.id shouldBe sourceID
      sources(1).folderMapping.position shouldBe 3000

      // move again should remain the same
      sourceService.moveSourceAfter(token, defaultFolderID, sourceID, sourceID2).unsafeRunSync()
      sources = sourceService.getSourcesInFolders(token, Seq(defaultFolderID), 20).compile.toList.unsafeRunSync()
      sources.length shouldBe 2
      sources.head.source.id shouldBe sourceID2
      sources.head.folderMapping.position shouldBe 2000
      sources(1).source.id shouldBe sourceID
      sources(1).folderMapping.position shouldBe 3000
    }

    it("should move source after another source if the target source is not the last one") {
      val sourceID = SourceID(url)
      val sourceID2 = SourceID(url2)
      val sourceID3 = SourceID(url3)
      sourceService.importSource(token, url).unsafeRunSync() shouldBe sourceID
      sourceService.importSource(token, url2).unsafeRunSync() shouldBe sourceID2
      sourceService.importSource(token, url3).unsafeRunSync() shouldBe sourceID3
      sourceService.addSourceToFolder(token, defaultFolderID, sourceID, 1000).unsafeRunSync()
      sourceService.addSourceToFolder(token, defaultFolderID, sourceID2, 2000).unsafeRunSync()
      sourceService.addSourceToFolder(token, defaultFolderID, sourceID3, 3000).unsafeRunSync()
      var sources = sourceService.getSourcesInFolders(token, Seq(defaultFolderID), 20).compile.toList.unsafeRunSync()
      sources.length shouldBe 3

      // move source after
      sourceService.moveSourceAfter(token, defaultFolderID, sourceID, sourceID2).unsafeRunSync()
      sources = sourceService.getSourcesInFolders(token, Seq(defaultFolderID), 20).compile.toList.unsafeRunSync()
      sources.length shouldBe 3
      sources.head.source.id shouldBe sourceID2
      sources.head.folderMapping.position shouldBe 2000
      sources(1).source.id shouldBe sourceID
      sources(1).folderMapping.position shouldBe 2500
      sources(2).source.id shouldBe sourceID3
      sources(2).folderMapping.position shouldBe 3000
    }

    it("should move source after another source if the positions need to be cleanup") {
      val sourceID = SourceID(url)
      val sourceID2 = SourceID(url2)
      val sourceID3 = SourceID(url3)
      sourceService.importSource(token, url).unsafeRunSync() shouldBe sourceID
      sourceService.importSource(token, url2).unsafeRunSync() shouldBe sourceID2
      sourceService.importSource(token, url3).unsafeRunSync() shouldBe sourceID3
      sourceService.addSourceToFolder(token, defaultFolderID, sourceID, 1).unsafeRunSync()
      sourceService.addSourceToFolder(token, defaultFolderID, sourceID2, 2).unsafeRunSync()
      sourceService.addSourceToFolder(token, defaultFolderID, sourceID3, 3).unsafeRunSync()
      var sources = sourceService.getSourcesInFolders(token, Seq(defaultFolderID), 20).compile.toList.unsafeRunSync()
      sources.length shouldBe 3

      // move source after
      sourceService.moveSourceAfter(token, defaultFolderID, sourceID, sourceID2).unsafeRunSync()
      sources = sourceService.getSourcesInFolders(token, Seq(defaultFolderID), 20).compile.toList.unsafeRunSync()
      sources.length shouldBe 3
      sources.head.source.id shouldBe sourceID2
      sources.head.folderMapping.position shouldBe 2000
      sources(1).source.id shouldBe sourceID
      sources(1).folderMapping.position shouldBe 2500
      sources(2).source.id shouldBe sourceID3
      sources(2).folderMapping.position shouldBe 3000
    }


    it("should move source before another source if the target source is the first one") {
      val sourceID = SourceID(url)
      val sourceID2 = SourceID(url2)
      sourceService.importSource(token, url).unsafeRunSync() shouldBe sourceID
      sourceService.importSource(token, url2).unsafeRunSync() shouldBe sourceID2
      sourceDao.get(sourceID).unsafeRunSync().isDefined shouldBe true
      sourceDao.get(sourceID2).unsafeRunSync().isDefined shouldBe true
      sourceService.addSourceToFolder(token, defaultFolderID, sourceID, 1000).unsafeRunSync()
      sourceService.addSourceToFolder(token, defaultFolderID, sourceID2, 2000).unsafeRunSync()
      var sources = sourceService.getSourcesInFolders(token, Seq(defaultFolderID), 20).compile.toList.unsafeRunSync()
      sources.length shouldBe 2
      sources.head.source.id shouldBe sourceID
      sources.head.folderMapping.position shouldBe 1000
      sources(1).source.id shouldBe sourceID2
      sources(1).folderMapping.position shouldBe 2000

      // move source before
      sourceService.moveSourceBefore(token, defaultFolderID, sourceID2, sourceID).unsafeRunSync()
      sources = sourceService.getSourcesInFolders(token, Seq(defaultFolderID), 20).compile.toList.unsafeRunSync()
      sources.length shouldBe 2
      sources.head.source.id shouldBe sourceID2
      sources.head.folderMapping.position shouldBe 500
      sources(1).source.id shouldBe sourceID
      sources(1).folderMapping.position shouldBe 1000

      // move again should remain the same
      sourceService.moveSourceBefore(token, defaultFolderID, sourceID2, sourceID).unsafeRunSync()
      sources = sourceService.getSourcesInFolders(token, Seq(defaultFolderID), 20).compile.toList.unsafeRunSync()
      sources.length shouldBe 2
      sources.head.source.id shouldBe sourceID2
      sources.head.folderMapping.position shouldBe 500
      sources(1).source.id shouldBe sourceID
      sources(1).folderMapping.position shouldBe 1000
    }

    it("should move source before another source if the target source is not the first one") {
      val sourceID = SourceID(url)
      val sourceID2 = SourceID(url2)
      val sourceID3 = SourceID(url3)
      sourceService.importSource(token, url).unsafeRunSync() shouldBe sourceID
      sourceService.importSource(token, url2).unsafeRunSync() shouldBe sourceID2
      sourceService.importSource(token, url3).unsafeRunSync() shouldBe sourceID3
      sourceService.addSourceToFolder(token, defaultFolderID, sourceID, 1000).unsafeRunSync()
      sourceService.addSourceToFolder(token, defaultFolderID, sourceID2, 2000).unsafeRunSync()
      sourceService.addSourceToFolder(token, defaultFolderID, sourceID3, 3000).unsafeRunSync()
      var sources = sourceService.getSourcesInFolders(token, Seq(defaultFolderID), 20).compile.toList.unsafeRunSync()
      sources.length shouldBe 3

      // move source before
      sourceService.moveSourceBefore(token, defaultFolderID, sourceID2, sourceID).unsafeRunSync()
      sources = sourceService.getSourcesInFolders(token, Seq(defaultFolderID), 20).compile.toList.unsafeRunSync()
      sources.length shouldBe 3
      sources.head.source.id shouldBe sourceID2
      sources.head.folderMapping.position shouldBe 500
      sources(1).source.id shouldBe sourceID
      sources(1).folderMapping.position shouldBe 1000
      sources(2).source.id shouldBe sourceID3
      sources(2).folderMapping.position shouldBe 3000
    }

    it("should move source before another source if the positions need to be cleanup") {
      val sourceID = SourceID(url)
      val sourceID2 = SourceID(url2)
      val sourceID3 = SourceID(url3)
      sourceService.importSource(token, url).unsafeRunSync() shouldBe sourceID
      sourceService.importSource(token, url2).unsafeRunSync() shouldBe sourceID2
      sourceService.importSource(token, url3).unsafeRunSync() shouldBe sourceID3
      sourceService.addSourceToFolder(token, defaultFolderID, sourceID, 1).unsafeRunSync()
      sourceService.addSourceToFolder(token, defaultFolderID, sourceID2, 2).unsafeRunSync()
      sourceService.addSourceToFolder(token, defaultFolderID, sourceID3, 3).unsafeRunSync()
      var sources = sourceService.getSourcesInFolders(token, Seq(defaultFolderID), 20).compile.toList.unsafeRunSync()
      sources.length shouldBe 3

      // move source before
      sourceService.moveSourceBefore(token, defaultFolderID, sourceID2, sourceID).unsafeRunSync()
      sources = sourceService.getSourcesInFolders(token, Seq(defaultFolderID), 20).compile.toList.unsafeRunSync()
      sources.length shouldBe 3
      sources.head.source.id shouldBe sourceID2
      sources.head.folderMapping.position shouldBe 500
      sources(1).source.id shouldBe sourceID
      sources(1).folderMapping.position shouldBe 1000
      sources(2).source.id shouldBe sourceID3
      sources(2).folderMapping.position shouldBe 3000
    }


  }
}
