package me.binwang.rss.service

import cats.effect.unsafe.IORuntime
import cats.effect.{IO, Resource}
import me.binwang.rss.dao.sql._
import me.binwang.rss.dao.{FolderDao, SourceDao}
import me.binwang.rss.generator.ConnectionPoolManager.connectionPool
import me.binwang.rss.generator.{Folders, Sources}
import me.binwang.rss.model._
import me.binwang.rss.sourcefinder.MultiSourceFinder
import me.binwang.rss.util.Throttler
import org.scalamock.scalatest.MockFactory
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

import java.io.{ByteArrayInputStream, InputStream}
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.util.UUID

class FolderServiceSpec extends AnyFunSpec with BeforeAndAfterEach with BeforeAndAfterAll
  with Matchers with MockFactory {

  implicit val ioRuntime: IORuntime = IORuntime.global
  implicit val loggerFactory: LoggerFactory[IO] = Slf4jFactory.create[IO]
  private implicit val userSessionDao: UserSessionSqlDao = new UserSessionSqlDao()
  private implicit val folderDao: FolderSqlDao = new FolderSqlDao()
  private val sourceDao = new SourceSqlDao()
  private val folderSourceDao = new FolderSourceSqlDao()
  private val moreLikeThisMappingDao = new MoreLikeThisMappingSqlDao()
  private val sourceFinder = new MultiSourceFinder(Seq())
  private implicit val authorizer: Authorizer = new Authorizer(Throttler(), userSessionDao, folderDao)

  private val folderService = new FolderService(folderDao, folderSourceDao, sourceDao, authorizer)
  private val sourceService = new SourceService(sourceDao, folderSourceDao, folderDao, null, authorizer, sourceFinder)
  private val moreLikeThisService = new MoreLikeThisService(moreLikeThisMappingDao, authorizer)

  private val testFile = "/opml-test.xml"
  private val totalSourcesInTestFile = 51
  private val folderlessXmlUrlInTestFile = "http://thenewstack.io/blog/feed/"

  private val token = UUID.randomUUID().toString
  private val adminToken= UUID.randomUUID().toString
  private val userID = UUID.randomUUID().toString
  private val adminUserID = UUID.randomUUID().toString

  private def inputStream: Resource[IO, InputStream] = {
    Resource.make {
      IO(getClass.getResourceAsStream(testFile))
    } { i =>
      IO(i.close())
    }
  }

  private def inputStreamOf(content: String): Resource[IO, InputStream] = {
    Resource.make {
      IO(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)))
    } { i =>
      IO(i.close())
    }
  }

  override def beforeAll(): Unit = {
    folderDao.dropTable().unsafeRunSync()
    sourceDao.dropTable().unsafeRunSync()
    folderSourceDao.dropTable().unsafeRunSync()
    userSessionDao.dropTable().unsafeRunSync()
    moreLikeThisMappingDao.dropTable().unsafeRunSync()

    folderDao.createTable().unsafeRunSync()
    sourceDao.createTable().unsafeRunSync()
    folderSourceDao.createTable().unsafeRunSync()
    userSessionDao.createTable().unsafeRunSync()
    moreLikeThisMappingDao.createTable().unsafeRunSync()
  }

  override def beforeEach(): Unit = {
    folderDao.deleteAll().unsafeRunSync()
    sourceDao.deleteAll().unsafeRunSync()
    folderSourceDao.deleteAll().unsafeRunSync()
    userSessionDao.deleteAll().unsafeRunSync()
    moreLikeThisMappingDao.deleteAll().unsafeRunSync()

    userSessionDao.insert(UserSession(token, userID, ZonedDateTime.now.plusDays(1), isAdmin = false,
      ZonedDateTime.now.plusDays(1))).unsafeRunSync()
    userSessionDao.insert(UserSession(adminToken, adminUserID, ZonedDateTime.now.plusDays(1), isAdmin = true,
      ZonedDateTime.now.plusDays(1))).unsafeRunSync()
  }

  private def createUserDefaultFolder(): Folder = {
    val folder = Folders.get(userID, 0, isUserDefault = true)
    folderDao.insertIfNotExist(folder).unsafeRunSync()
    folder
  }

  private def importTestFile(service: FolderService = folderService): ImportOPMLResult = {
    service.importFromOPML(token, inputStream).unsafeRunSync()
  }

  /**
   * The sources of the user with their folder mappings, sorted by folder and position so that the order doesn't
   * depend on the database.
   */
  private def myFolderSources: Seq[FolderSource] = {
    folderSourceDao.getSourcesByUser(userID, 1000)
      .compile.toList.unsafeRunSync()
      .sortBy(folderSource => (folderSource.folderMapping.folderID, folderSource.folderMapping.position))
  }

  /**
   * The imported names of the user, keyed by folder name so that it can be compared across imports, which create
   * folders with new IDs.
   */
  private def importedNamesByFolder: Set[(String, String, Option[String])] = {
    val folderNames = folderDao.listByUser(userID).compile.toList.unsafeRunSync()
      .map(folder => folder.id -> folder.name)
      .toMap
    myFolderSources.map { folderSource =>
      val mapping = folderSource.folderMapping
      (folderNames(mapping.folderID), mapping.sourceID, mapping.importedSourceName)
    }.toSet
  }

  private def folderID(name: String): String = {
    folderDao.getByUserAndName(userID, name).unsafeRunSync().get.id
  }

  private def mappingOf(inFolderID: String, xmlUrl: String): Option[FolderSource] = {
    folderSourceDao.get(inFolderID, SourceID(xmlUrl)).unsafeRunSync()
  }

  private def checkImported(): Unit = {
    val folders = folderService.getMyFolders(token, 100, 0)
      .compile.toList.unsafeRunSync()
      .filter(!_.isUserDefault)
    folders.length shouldBe 9
    folders.foreach { folder =>
      folder.name.isEmpty shouldBe false
      val sources = folderSourceDao.getSourcesByFolder(folder.id).compile.toList.unsafeRunSync()
      sources.isEmpty shouldBe false
    }
  }

  describe("Folder Service") {

    it("should import OPML file") {
      createUserDefaultFolder()
      val result = importTestFile()

      result.totalSources shouldBe totalSourcesInTestFile
      result.successfulSources shouldBe totalSourcesInTestFile
      result.failedSources shouldBe empty
      checkImported()
      myFolderSources.map(_.source.id).distinct.length shouldBe totalSourcesInTestFile
    }

    it("should store the imported name of each feed") {
      val defaultFolder = createUserDefaultFolder()
      importTestFile()

      val inFolder = mappingOf(folderID("tech"), "https://www.binwang.me/feed.xml").get
      inFolder.folderMapping.importedSourceName shouldBe Some("Bin Wang - My Personal Blog")
      inFolder.source.title shouldBe None
      inFolder.displayName shouldBe Some("Bin Wang - My Personal Blog")

      val inDefaultFolder = mappingOf(defaultFolder.id, folderlessXmlUrlInTestFile).get
      inDefaultFolder.folderMapping.importedSourceName shouldBe Some("The New Stack » Blog")

      myFolderSources.foreach { folderSource =>
        folderSource.folderMapping.importedSourceName.exists(_.trim.nonEmpty) shouldBe true
      }
    }

    it("should not change anything when importing the same OPML file twice") {
      createUserDefaultFolder()
      val firstResult = importTestFile()
      val foldersBefore = folderDao.listByUser(userID).compile.toList.unsafeRunSync()
      val folderSourcesBefore = myFolderSources

      val secondResult = importTestFile()

      secondResult shouldBe firstResult
      folderDao.listByUser(userID).compile.toList.unsafeRunSync() shouldBe foldersBefore
      val folderSourcesAfter = myFolderSources
      folderSourcesAfter shouldBe folderSourcesBefore
      folderSourcesAfter.length shouldBe folderSourcesBefore.length
      folderSourcesAfter.map { folderSource =>
        (folderSource.folderMapping.folderID, folderSource.folderMapping.sourceID)
      }.distinct.length shouldBe folderSourcesAfter.length
    }

    it("should report a feed that could not be stored and add it on retry") {
      val failedXmlUrl = "https://www.binwang.me/feed.xml"
      val mockSourceDao = mock[SourceDao]
      (mockSourceDao.insert _)
        .expects(where {source: Source => source.xmlUrl.equals(failedXmlUrl)})
        .returning(IO.raiseError(new Exception("Mock insert source exception")))
        .anyNumberOfTimes()
      (mockSourceDao.insert _)
        .expects(*)
        .onCall {source: Source => sourceDao.insert(source)}
        .anyNumberOfTimes()

      createUserDefaultFolder()
      val mockFolderService = new FolderService(folderDao, folderSourceDao, mockSourceDao, authorizer)
      val firstResult = importTestFile(mockFolderService)

      firstResult.totalSources shouldBe totalSourcesInTestFile
      firstResult.failedSources.map(_.xmlUrl) shouldBe Seq(failedXmlUrl)
      firstResult.successfulSources + firstResult.failedSources.size shouldBe firstResult.totalSources
      val error = firstResult.failedSources.head.error
      error should include("could not be added because of a storage error")
      error should include("Import the same file again")
      error should not include "Mock insert source exception"
      mappingOf(folderID("tech"), failedXmlUrl) shouldBe None
      val sourcesAfterFailure = myFolderSources

      val retryResult = importTestFile()

      retryResult.failedSources shouldBe empty
      retryResult.successfulSources shouldBe totalSourcesInTestFile
      mappingOf(folderID("tech"), failedXmlUrl).map(_.folderMapping.importedSourceName) shouldBe
        Some(Some("Bin Wang - My Personal Blog"))
      val sourcesAfterRetry = myFolderSources
      sourcesAfterRetry.length shouldBe sourcesAfterFailure.length + 1
      sourcesAfterRetry.map(_.source.id).distinct.length shouldBe totalSourcesInTestFile
    }

    it("should report the feeds of a folder that could not be created or found") {
      val brokenFolderName = "news"
      // In the test file this feed is only in the "news" folder.
      val feedOnlyInBrokenFolder = "https://rsshub.app/reuters/channel/cn/analyses"
      val mockFolderDao = mock[FolderDao]
      (mockFolderDao.insertIfNotExist _)
        .expects(where {folder: Folder => folder.name.equals(brokenFolderName)})
        .returning(IO.pure(false))
        .atLeastOnce()
      (mockFolderDao.insertIfNotExist _)
        .expects(*)
        .onCall {folder: Folder => folderDao.insertIfNotExist(folder)}
        .anyNumberOfTimes()
      (mockFolderDao.getByUserAndName _)
        .expects(*, brokenFolderName)
        .returning(IO.pure(None))
        .atLeastOnce()
      (mockFolderDao.getUserDefaultFolder _)
        .expects(*)
        .onCall {uid: String => folderDao.getUserDefaultFolder(uid)}
        .anyNumberOfTimes()

      createUserDefaultFolder()
      val mockFolderService = new FolderService(mockFolderDao, folderSourceDao, sourceDao, authorizer)
      val result = importTestFile(mockFolderService)

      result.totalSources shouldBe totalSourcesInTestFile
      result.failedSources.map(_.xmlUrl) shouldBe Seq(feedOnlyInBrokenFolder)
      result.successfulSources + result.failedSources.size shouldBe result.totalSources
      val error = result.failedSources.head.error
      error should include(s""""$brokenFolderName" could not be created or found""")
      error should include("Import the same file again")

      folderDao.getByUserAndName(userID, brokenFolderName).unsafeRunSync() shouldBe None
      folderDao.listByUser(userID).compile.toList.unsafeRunSync().count(!_.isUserDefault) shouldBe 8
      folderSourceDao.getSourcesByID(userID, SourceID(feedOnlyInBrokenFolder))
        .compile.toList.unsafeRunSync() shouldBe empty
      // These feeds are also in the "kindle" folder, so they are still subscribed from there.
      Seq("https://rsshub.app/scmp/4", "https://rsshub.binwang.me/cbc/topics/canada-toronto").foreach { xmlUrl =>
        folderSourceDao.getSourcesByID(userID, SourceID(xmlUrl)).compile.toList.unsafeRunSync().length shouldBe 1
      }
    }

    it("should not touch a source that already exists") {
      val xmlUrl = "https://freedom-to-tinker.com/feed/rss/"
      val existingSource = Sources.get(maybeXmlUrl = Some(xmlUrl)).copy(
        fetchFailedMsg = Some("The feed is broken"),
        fetchCompletedAt = Some(ZonedDateTime.now().minusDays(1).withNano(0)),
        fetchScheduledAt = ZonedDateTime.now().plusDays(30).withNano(0),
        fetchErrorCount = 5,
      )
      sourceDao.insert(existingSource).unsafeRunSync()
      createUserDefaultFolder()

      val result = importTestFile()

      result.failedSources shouldBe empty
      sourceDao.get(existingSource.id).unsafeRunSync() shouldBe Some(existingSource)
      folderSourceDao.getSourcesByID(userID, existingSource.id).compile.toList.unsafeRunSync()
        .map(_.folderMapping.importedSourceName) shouldBe Seq(Some("Freedom to Tinker"))
    }

    it("should fail before writing anything if the user has no default folder") {
      val error = folderService.importFromOPML(token, inputStream).attempt.unsafeRunSync().swap.toOption.get

      error.getMessage should include("default folder")
      folderDao.listByUser(userID).compile.toList.unsafeRunSync() shouldBe empty
      folderSourceDao.getSourcesByUser(userID, 1000).compile.toList.unsafeRunSync() shouldBe empty
      sourceDao.get(SourceID(folderlessXmlUrlInTestFile)).unsafeRunSync() shouldBe None
    }

    it("should export OPML file") {
      val defaultFolder = createUserDefaultFolder()
      importTestFile()
      val namesBeforeExport = importedNamesByFolder

      val opml = folderService.exportOPML(token).unsafeRunSync()

      folderDao.dropTable().unsafeRunSync()
      sourceDao.dropTable().unsafeRunSync()
      folderSourceDao.dropTable().unsafeRunSync()
      folderDao.createTable().unsafeRunSync()
      sourceDao.createTable().unsafeRunSync()
      folderSourceDao.createTable().unsafeRunSync()
      folderDao.insertIfNotExist(defaultFolder).unsafeRunSync()

      val importResult = folderService.importFromOPML(token, inputStreamOf(opml)).unsafeRunSync()
      importResult.failedSources shouldBe empty
      importResult.totalSources shouldBe totalSourcesInTestFile
      checkImported()

      importedNamesByFolder shouldBe namesBeforeExport
      // Nothing was fetched, so the exported and imported names can only come from the import label.
      myFolderSources.foreach { folderSource =>
        folderSource.source.title shouldBe None
        folderSource.folderMapping.importedSourceName.exists(_.trim.nonEmpty) shouldBe true
        folderSource.displayName shouldBe folderSource.folderMapping.importedSourceName
      }
    }

    it("should add folder") {
      val folder = Folders.getCreator(0)
      folderService.addFolder(token, folder).unsafeRunSync()
      val folders = folderService.getMyFolders(token, 100, 0).compile.toList.unsafeRunSync()
      folders.length shouldBe 1
      folders.head.userID shouldBe userID // userID will be assigned by server
    }

    it("should get recommended folders") {
      val folderCreate1 = Folders.getCreator(0)
      val folderCreate2 = Folders.getCreator(1)
      val folderCreate3 = Folders.getCreator(2)
      val folder1 = folderService.addFolder(adminToken, folderCreate1).unsafeRunSync()
      val folder2 = folderService.addFolder(adminToken, folderCreate2).unsafeRunSync()
      folderService.updateFolder(adminToken, folder1.id,
        FolderUpdater(recommend = Some(true), language = Some(Some(Language.ENGLISH)))).unsafeRunSync()
      folderService.updateFolder(adminToken, folder2.id, FolderUpdater(recommend = Some(true))).unsafeRunSync()
      val folders = folderService.getRecommendFolders(token, Language.ENGLISH, 10, -1).compile.toList.unsafeRunSync()
      folders.length shouldBe 2
      folders.head.id shouldBe folder1.id
      folders(1).id shouldBe folder2.id
    }

    it("should fail to add folder with same name") {
      val folder = Folders.getCreator(0)
      folderService.addFolder(token, folder).unsafeRunSync()
      try {
        folderService.addFolder(token, folder).unsafeRunSync()
        throw new Exception("Should throw folder duplicate error")
      } catch {
        case _: FolderDuplicateError =>
      }
    }

    it("should delete folder") {
      val folder = Folders.getCreator(0)
      val source = Sources.get()
      val addedFolder = folderService.addFolder(token, folder).unsafeRunSync()
      sourceService.addSourceToFolder(token, addedFolder.id, source.id, 0)
      var folders = folderService.getMyFolders(token, 100, 0).compile.toList.unsafeRunSync()
      folders.length shouldBe 1
      folders.head.userID shouldBe userID // userID will be assigned by server
      folderService.deleteFolder(token, addedFolder.id).unsafeRunSync()
      folders = folderService.getMyFolders(token, 100, 0).compile.toList.unsafeRunSync()
      folders.length shouldBe 0
      folderSourceDao.getSourcesByFolder(addedFolder.id).compile.toList.unsafeRunSync().length shouldBe 0
    }


    it("should add and get more like this mappings") {
      val sourceID = SourceID("http://testurl.com")

      val folder = folderService.addFolder(token, Folders.getCreator(0)).unsafeRunSync()
      val folder2 = folderService.addFolder(token, Folders.getCreator(1)).unsafeRunSync()

      moreLikeThisService.addMoreLikeThisMapping(
        token, folder.id, MoreLikeThisType.FOLDER, sourceID, MoreLikeThisType.SOURCE, 1).unsafeRunSync()
      var mappings = moreLikeThisService.getMoreLikeThisMappings(token, folder.id, MoreLikeThisType.FOLDER, 10, 0)
        .compile.toList.unsafeRunSync()
      mappings.length shouldBe 1
      mappings.head.moreLikeThisID shouldBe sourceID
      mappings.head.moreLikeThisType shouldBe MoreLikeThisType.SOURCE

      moreLikeThisService.addMoreLikeThisMapping(
        token, folder.id, MoreLikeThisType.FOLDER, folder2.id, MoreLikeThisType.FOLDER, 2).unsafeRunSync()
      mappings = moreLikeThisService.getMoreLikeThisMappings(token, folder.id, MoreLikeThisType.FOLDER, 10, 0)
        .compile.toList.unsafeRunSync()
      mappings.length shouldBe 2
      mappings.head.moreLikeThisID shouldBe sourceID
      mappings.head.moreLikeThisType shouldBe MoreLikeThisType.SOURCE
      mappings(1).moreLikeThisID shouldBe folder2.id
      mappings(1).moreLikeThisType shouldBe MoreLikeThisType.FOLDER

      moreLikeThisService.delMoreLikeThisMapping(
        token, folder.id, MoreLikeThisType.FOLDER, sourceID, MoreLikeThisType.SOURCE).unsafeRunSync()
      mappings = moreLikeThisService.getMoreLikeThisMappings(token, folder.id, MoreLikeThisType.FOLDER, 10, 0)
        .compile.toList.unsafeRunSync()
      mappings.length shouldBe 1
      mappings.head.moreLikeThisID shouldBe folder2.id
      mappings.head.moreLikeThisType shouldBe MoreLikeThisType.FOLDER

      moreLikeThisService.delMoreLikeThisMapping(
        token, folder.id, MoreLikeThisType.FOLDER, folder2.id, MoreLikeThisType.FOLDER).unsafeRunSync()
      mappings = moreLikeThisService.getMoreLikeThisMappings(token, folder.id, MoreLikeThisType.FOLDER, 10, 0)
        .compile.toList.unsafeRunSync()
      mappings.length shouldBe 0
    }

  }
}
