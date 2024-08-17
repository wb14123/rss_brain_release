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
  private val importSourcesTaskDao = new ImportSourcesTaskSqlDao()
  private val sourceFinder = new MultiSourceFinder(Seq())
  private implicit val authorizer: Authorizer = new Authorizer(Throttler(), userSessionDao, folderDao)

  private val folderService = new FolderService(folderDao, folderSourceDao, sourceDao, importSourcesTaskDao, authorizer)
  private val sourceService = new SourceService(sourceDao, folderSourceDao, folderDao, null, authorizer, sourceFinder)
  private val moreLikeThisService = new MoreLikeThisService(moreLikeThisMappingDao, authorizer)

  private val testFile = "/opml-test.xml"

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


  override def beforeAll(): Unit = {
    folderDao.dropTable().unsafeRunSync()
    sourceDao.dropTable().unsafeRunSync()
    folderSourceDao.dropTable().unsafeRunSync()
    userSessionDao.dropTable().unsafeRunSync()
    moreLikeThisMappingDao.dropTable().unsafeRunSync()
    importSourcesTaskDao.dropTable().unsafeRunSync()

    folderDao.createTable().unsafeRunSync()
    sourceDao.createTable().unsafeRunSync()
    folderSourceDao.createTable().unsafeRunSync()
    userSessionDao.createTable().unsafeRunSync()
    moreLikeThisMappingDao.createTable().unsafeRunSync()
    importSourcesTaskDao.createTable().unsafeRunSync()
  }

  override def beforeEach(): Unit = {
    folderDao.deleteAll().unsafeRunSync()
    sourceDao.deleteAll().unsafeRunSync()
    folderSourceDao.deleteAll().unsafeRunSync()
    userSessionDao.deleteAll().unsafeRunSync()
    moreLikeThisMappingDao.deleteAll().unsafeRunSync()
    importSourcesTaskDao.deleteAll().unsafeRunSync()

    userSessionDao.insert(UserSession(token, userID, ZonedDateTime.now.plusDays(1), isAdmin = false,
      ZonedDateTime.now.plusDays(1))).unsafeRunSync()
    userSessionDao.insert(UserSession(adminToken, adminUserID, ZonedDateTime.now.plusDays(1), isAdmin = true,
      ZonedDateTime.now.plusDays(1))).unsafeRunSync()
  }

  private def checkImported(userID: String): Unit = {
    val folders = folderService.getMyFolders(token, 10, 0)
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
      val folder = Folders.get(userID, 0, isUserDefault = true)
      folderDao.insertIfNotExist(folder).unsafeRunSync()

      folderService.importFromOPML(token, inputStream).unsafeRunSync()
      checkImported(userID)
    }

    it("should export OPML file") {
      val folder = Folders.get(userID, 0, isUserDefault = true)
      folderDao.insertIfNotExist(folder).unsafeRunSync()

      folderService.importFromOPML(token, inputStream).unsafeRunSync()

      val folders1 = folderService.getMyFolders(token, 10, 0)
        .compile.toList.unsafeRunSync()
        .filter(!_.isUserDefault)
      val opml = folderService.exportOPML(token).unsafeRunSync()

      folderDao.dropTable().unsafeRunSync()
      sourceDao.dropTable().unsafeRunSync()
      folderSourceDao.dropTable().unsafeRunSync()
      folderDao.createTable().unsafeRunSync()
      sourceDao.createTable().unsafeRunSync()
      folderSourceDao.createTable().unsafeRunSync()
      folderDao.insertIfNotExist(folder).unsafeRunSync()

      val exportedInputStream = Resource.make {
        IO(new ByteArrayInputStream(opml.getBytes(StandardCharsets.UTF_8)))
      } { i =>
        IO(i.close())
      }
      folderService.deleteOPMLImportTasks(token).unsafeRunSync()
      folderService.importFromOPML(token, exportedInputStream).unsafeRunSync()
      val folders2 = folderService.getMyFolders(token, 10, 0)
        .compile.toList.unsafeRunSync()
        .filter(!_.isUserDefault)

      folders2.nonEmpty shouldBe true

      val folders1Names = folders1.map(_.name).toSet
      val folders2Names = folders2.map(_.name).toSet

      folders1Names shouldBe folders2Names
      checkImported(userID)
    }

    it("should be able to import OPML file twice") {
      val folder = Folders.get(userID, 0, isUserDefault = true)
      folderDao.insertIfNotExist(folder).unsafeRunSync()

      folderService.importFromOPML(token, inputStream).unsafeRunSync()
      folderService.deleteOPMLImportTasks(token).unsafeRunSync()
      folderService.importFromOPML(token, inputStream).unsafeRunSync()

      checkImported(userID)
    }

    ignore("should throw error if user doesn't have default folder") {
      val userID = UUID.randomUUID().toString
      folderService.importFromOPML(token, inputStream).unsafeRunSync()
      folderService.getImportOPMLTask(token).unsafeRunSync().failedSources shouldBe 1
      checkImported(userID)
    }

    it("should handle source update failure and recover") {
      val xmlUrl = "https://www.binwang.me/feed.xml"
      val mockSourceDao = mock[SourceDao]
      (mockSourceDao.insert _)
        .expects(where {source: Source => source.xmlUrl.equals(xmlUrl)})
        .returning(IO.raiseError(new Exception("Mock insert source exception")))
        .anyNumberOfTimes()
      (mockSourceDao.insert _)
        .expects(*)
        .onCall {source: Source => sourceDao.insert(source)}
        .anyNumberOfTimes()

      val folder = Folders.get(userID, 0, isUserDefault = true)
      folderDao.insertIfNotExist(folder).unsafeRunSync()

      val mockFoldService = new FolderService(folderDao, folderSourceDao, mockSourceDao, importSourcesTaskDao, authorizer)
      mockFoldService.importFromOPML(token, inputStream).unsafeRunSync()
      mockFoldService.getImportOPMLTask(token).unsafeRunSync().failedSources shouldBe 1
      checkImported(userID)
      val firstAttemptSources = folderSourceDao.getSourcesByUser(userID, 1000).compile.toList.unsafeRunSync().length

      folderService.deleteOPMLImportTasks(token).unsafeRunSync()
      folderService.importFromOPML(token, inputStream).unsafeRunSync()
      folderService.getImportOPMLTask(token).unsafeRunSync().failedSources shouldBe 0
      checkImported(userID)
      val secondAttemptSources = folderSourceDao.getSourcesByUser(userID, 1000).compile.toList.unsafeRunSync().length

      firstAttemptSources + 1 shouldBe secondAttemptSources
    }

    it("should handle folder update failure") {
      val mockFolderDao = mock[FolderDao]
      (mockFolderDao.insertIfNotExist _)
        .expects(where {folder: Folder => folder.name.equals("news")})
        .returning(IO.raiseError(new Exception("Mock insert folder error")))
        .atLeastOnce()
      (mockFolderDao.insertIfNotExist _)
        .expects(*)
        .onCall {folder: Folder => folderDao.insertIfNotExist(folder)}
        .atLeastOnce()
      (mockFolderDao.getUserDefaultFolder _)
        .expects(*)
        .onCall {userID: String => folderDao.getUserDefaultFolder(userID)}
        .atLeastOnce()
      (mockFolderDao.getByUserAndName _)
        .expects(*, *)
        .onCall {(userID: String, name: String) => folderDao.getByUserAndName(userID, name)}
        .anyNumberOfTimes()

      val folder = Folders.get(userID, 0, isUserDefault = true)
      folderDao.insertIfNotExist(folder).unsafeRunSync()

      val mockFoldService = new FolderService(mockFolderDao, folderSourceDao, sourceDao, importSourcesTaskDao, authorizer)
      mockFoldService.importFromOPML(token, inputStream).unsafeRunSync()
      mockFoldService.getImportOPMLTask(token).unsafeRunSync().failedSources shouldBe 5
      val firstAttemptSources = folderSourceDao.getSourcesByUser(userID, 1000).compile.toList.unsafeRunSync().length
      val firstAttemptFolder = folderDao.listByUser(userID).compile.toList.unsafeRunSync().length

      folderService.deleteOPMLImportTasks(token).unsafeRunSync()
      folderService.importFromOPML(token, inputStream).unsafeRunSync()
      mockFoldService.getImportOPMLTask(token).unsafeRunSync().failedSources shouldBe 0
      checkImported(userID)
      val secondAttemptSources = folderSourceDao.getSourcesByUser(userID, 1000).compile.toList.unsafeRunSync().length
      val secondAttemptFolder = folderDao.listByUser(userID).compile.toList.unsafeRunSync().length

      firstAttemptFolder + 1 shouldBe secondAttemptFolder
      firstAttemptSources + 5 shouldBe secondAttemptSources
    }

    it("should handle folder not found") {
      val mockFolderDao = mock[FolderDao]
      (mockFolderDao.insertIfNotExist _)
        .expects(where {folder: Folder => folder.name.equals("news")})
        .returning(IO.pure(false))
        .atLeastOnce()
      (mockFolderDao.insertIfNotExist _)
        .expects(*)
        .onCall {folder: Folder => folderDao.insertIfNotExist(folder)}
        .atLeastOnce()
      (mockFolderDao.getUserDefaultFolder _)
        .expects(*)
        .onCall {userID: String => folderDao.getUserDefaultFolder(userID)}
        .atLeastOnce()
      (mockFolderDao.getByUserAndName _)
        .expects(*, *)
        .onCall {(userID: String, name: String) => folderDao.getByUserAndName(userID, name)}
        .atLeastOnce()

      val folder = Folders.get(userID, 0, isUserDefault = true)
      folderDao.insertIfNotExist(folder).unsafeRunSync()

      val mockFoldService = new FolderService(mockFolderDao, folderSourceDao, sourceDao, importSourcesTaskDao, authorizer)
      mockFoldService.importFromOPML(token, inputStream).unsafeRunSync()
      mockFoldService.getImportOPMLTask(token).unsafeRunSync().failedSources shouldBe 5
      val firstAttemptSources = folderSourceDao.getSourcesByUser(userID, 1000).compile.toList.unsafeRunSync().length
      val firstAttemptFolder = folderDao.listByUser(userID).compile.toList.unsafeRunSync().length

      folderService.deleteOPMLImportTasks(token).unsafeRunSync()
      folderService.importFromOPML(token, inputStream).unsafeRunSync()
      folderService.getImportOPMLTask(token).unsafeRunSync().failedSources shouldBe 0
      checkImported(userID)
      val secondAttemptSources = folderSourceDao.getSourcesByUser(userID, 1000).compile.toList.unsafeRunSync().length
      val secondAttemptFolder = folderDao.listByUser(userID).compile.toList.unsafeRunSync().length

      firstAttemptFolder + 1 shouldBe secondAttemptFolder
      firstAttemptSources + 5 shouldBe secondAttemptSources
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
