package me.binwang.rss.dao.sql

import cats.effect.unsafe.IORuntime
import me.binwang.rss.generator.ConnectionPoolManager.connectionPool
import me.binwang.rss.generator.Sources
import me.binwang.rss.model.{FetchStatus, FolderSource, FolderSourceMapping, FolderSourceMappingUpdater, Source}
import org.scalacheck.Gen
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class FolderSourceSqlDaoSpec extends AnyFunSpec with BeforeAndAfterEach with BeforeAndAfterAll with Matchers {

  implicit val ioRuntime: IORuntime = IORuntime.global

  private val folderSourceSqlDao = new FolderSourceSqlDao()
  private val sourceSqlDao = new SourceSqlDao()
  private val userId = "user_id"

  override def beforeAll(): Unit = {
    sourceSqlDao.dropTable().unsafeRunSync()
    folderSourceSqlDao.dropTable().unsafeRunSync()
    sourceSqlDao.createTable().unsafeRunSync()
    folderSourceSqlDao.createTable().unsafeRunSync()
  }

  override def beforeEach(): Unit = {
    sourceSqlDao.deleteAll().unsafeRunSync()
    folderSourceSqlDao.deleteAll().unsafeRunSync()
  }

  private def insertSources(sourceXmlUrl1: Option[String] = None, sourceXmlUrl2: Option[String] = None): (Source, Source, String) = {
    val source1 = Sources.get(maybeXmlUrl = sourceXmlUrl1)
    val source2 = Sources.get(maybeXmlUrl = sourceXmlUrl2)
    val folderId = Gen.uuid.sample.get.toString
    sourceSqlDao.insert(source1).unsafeRunSync()
    sourceSqlDao.insert(source2).unsafeRunSync()
    folderSourceSqlDao.addSourceToFolder(FolderSourceMapping(folderId, source1.id, userId, 0)).unsafeRunSync() shouldBe true
    folderSourceSqlDao.addSourceToFolder(FolderSourceMapping(folderId, source2.id, userId, 1)).unsafeRunSync() shouldBe true
    folderSourceSqlDao.addSourceToFolder(FolderSourceMapping(folderId, source2.id, userId, 2)).unsafeRunSync() shouldBe false
    (source1, source2, folderId)
  }

  describe("FolderSource SQL DAO") {

    it("should insert and get sources for folder") {
      val (source1, source2, folderId) = insertSources()

      val sources = folderSourceSqlDao.getSourcesByFolder(folderId).compile.toList.unsafeRunSync()
      sources.length shouldBe 2
      sources.head.folderMapping.folderID shouldBe folderId
      sources.head.source shouldBe source1
      sources(1).folderMapping.folderID shouldBe folderId
      sources(1).source shouldBe source2

      val first = folderSourceSqlDao.getSourcesByFolder(folderId, 1, -1).compile.toList.unsafeRunSync()
      first.length shouldBe 1
      first.head.source shouldBe source1
      first.head.folderMapping.showTitle shouldBe true
      first.head.folderMapping.showFullArticle shouldBe false

      val second = folderSourceSqlDao.getSourcesByFolder(folderId, 1, 0).compile.toList.unsafeRunSync()
      second.length shouldBe 1
      second.head.source shouldBe source2

      val third = folderSourceSqlDao.getSourcesByFolder(folderId, 1, 1).compile.toList.unsafeRunSync()
      third.length shouldBe 0
    }

    it("should cleanup source position") {
      val (source1, source2, folderId) = insertSources()
      val (source3, source4, folderId2) = insertSources(Some(source1.xmlUrl), Some(source2.xmlUrl))

      source1.id shouldBe source3.id
      source2.id shouldBe source4.id

      var getSources: Seq[FolderSource] = Seq()
      getSources = folderSourceSqlDao.getSourcesByFolder(folderId, 100, -1).compile.toList.unsafeRunSync()
      getSources.length shouldBe 2
      getSources.head.folderMapping.position shouldBe 0
      getSources(1).folderMapping.position shouldBe 1

      getSources = folderSourceSqlDao.getSourcesByFolder(folderId2, 100, -1).compile.toList.unsafeRunSync()
      getSources.length shouldBe 2
      getSources.head.folderMapping.position shouldBe 0
      getSources(1).folderMapping.position shouldBe 1

      folderSourceSqlDao.cleanupPositionInFolder(folderId).unsafeRunSync()
      getSources = folderSourceSqlDao.getSourcesByFolder(folderId, 100, -1).compile.toList.unsafeRunSync()
      getSources.length shouldBe 2
      getSources.head.folderMapping.position shouldBe 1000
      getSources(1).folderMapping.position shouldBe 2000

      // shouldn't affect orders in other folders
      getSources = folderSourceSqlDao.getSourcesByFolder(folderId2, 100, -1).compile.toList.unsafeRunSync()
      getSources.length shouldBe 2
      getSources.head.folderMapping.position shouldBe 0
      getSources(1).folderMapping.position shouldBe 1
    }


    it("should copy folder sources") {
      val (source1, source2, fromFolderId) = insertSources()
      val folderId = Gen.uuid.sample.get.toString
      folderSourceSqlDao.copySources(fromFolderId, folderId).unsafeRunSync() shouldBe 2

      val sources = folderSourceSqlDao.getSourcesByFolder(folderId).compile.toList.unsafeRunSync()

      sources.length shouldBe 2
      sources.head.folderMapping.folderID shouldBe folderId
      sources.head.source shouldBe source1
      sources(1).folderMapping.folderID shouldBe folderId
      sources(1).source shouldBe source2

      val first = folderSourceSqlDao.getSourcesByFolder(folderId, 1, -1).compile.toList.unsafeRunSync()
      first.length shouldBe 1
      first.head.source shouldBe source1

      val second = folderSourceSqlDao.getSourcesByFolder(folderId, 1, 0).compile.toList.unsafeRunSync()
      second.length shouldBe 1
      second.head.source shouldBe source2

      val third = folderSourceSqlDao.getSourcesByFolder(folderId, 1, 1).compile.toList.unsafeRunSync()
      third.length shouldBe 0
    }

    it("should insert and get sources by user") {
      val source1 = Sources.get()
      val source2 = Sources.get()
      val folderId1 = Gen.uuid.sample.get.toString
      val folderId2 = Gen.uuid.sample.get.toString
      sourceSqlDao.insert(source1).unsafeRunSync()
      sourceSqlDao.insert(source2).unsafeRunSync()
      folderSourceSqlDao.addSourceToFolder(FolderSourceMapping(folderId1, source1.id, "user1", 0)).unsafeRunSync() shouldBe true
      folderSourceSqlDao.addSourceToFolder(FolderSourceMapping(folderId2, source2.id, "user2", 1)).unsafeRunSync() shouldBe true
      folderSourceSqlDao.addSourceToFolder(FolderSourceMapping(folderId2, source2.id, "user2", 2)).unsafeRunSync() shouldBe false

      val user1Sources = folderSourceSqlDao.getSourcesByUser("user1").compile.toList.unsafeRunSync()
      user1Sources.length shouldBe 1
      user1Sources.head.folderMapping.folderID shouldBe folderId1
      user1Sources.head.source shouldBe source1

      val user2Sources = folderSourceSqlDao.getSourcesByUser("user2").compile.toList.unsafeRunSync()
      user2Sources.length shouldBe 1
      user2Sources.head.folderMapping.folderID shouldBe folderId2
      user2Sources.head.source shouldBe source2
    }

    it("should delete source from folder") {
      val (source1, source2, folderId) = insertSources()

      val sources = folderSourceSqlDao.getSourcesByFolder(folderId).compile.toList.unsafeRunSync()
      sources.head.source shouldBe source1
      sources(1).source shouldBe source2

      folderSourceSqlDao.delSourceFromFolder(folderId, source1.id).unsafeRunSync() shouldBe true
      val deletedSources = folderSourceSqlDao.getSourcesByFolder(folderId).compile.toList.unsafeRunSync()
      deletedSources.length shouldBe 1
      deletedSources.head.source shouldBe source2

      folderSourceSqlDao.delSourceFromFolder(folderId, source1.id).unsafeRunSync() shouldBe false
      val d2Sources = folderSourceSqlDao.getSourcesByFolder(folderId).compile.toList.unsafeRunSync()
      d2Sources.length shouldBe 1
    }

    it("should update position") {
      val (source1, source2, folderId) = insertSources()

      val sources = folderSourceSqlDao.getSourcesByFolder(folderId).compile.toList.unsafeRunSync()
      sources.head.source shouldBe source1
      sources(1).source shouldBe source2

      folderSourceSqlDao.updateSourceOrder(folderId, source1.id, 3).unsafeRunSync()
      val reorderSources = folderSourceSqlDao.getSourcesByFolder(folderId).compile.toList.unsafeRunSync()
      reorderSources.head.source shouldBe source2
      reorderSources(1).source shouldBe source1
    }

    it("should update custom source name") {
      val (source1, source2, folderId) = insertSources()

      val sources = folderSourceSqlDao.getSourcesByFolder(folderId).compile.toList.unsafeRunSync()
      sources.head.folderMapping.customSourceName shouldBe None

      val customName = "custom_name"
      folderSourceSqlDao.updateSourceInfo(userId, source1.id,
        FolderSourceMappingUpdater(customSourceName = Some(Some(customName)))).unsafeRunSync()
      val updatedSources = folderSourceSqlDao.getSourcesByFolder(folderId).compile.toList.unsafeRunSync()
      updatedSources.head.folderMapping.customSourceName shouldBe Some(customName)
    }

    it("should cleanup position") {
      val (source1, source2, folderId1) = insertSources()
      val (source3, source4, folderId2) = insertSources()
      folderSourceSqlDao.cleanupPositionInFolder(folderId1).unsafeRunSync()
      val sources1 = folderSourceSqlDao.getSourcesByFolder(folderId1).compile.toList.unsafeRunSync()
      sources1.head.source.id shouldBe source1.id
      sources1.head.folderMapping.position shouldBe 1000
      sources1(1).source.id shouldBe source2.id
      sources1(1).folderMapping.position shouldBe 2000

      val sources2 = folderSourceSqlDao.getSourcesByFolder(folderId2).compile.toList.unsafeRunSync()
      sources2.head.source.id shouldBe source3.id
      sources2.head.folderMapping.position shouldBe 0
    }

    it("should pause sources that are not in any folder") {
      val (source1, source2, folderId1) = insertSources()
      val (source3, source4, folderId2) = insertSources()

      sourceSqlDao.get(source1.id).unsafeRunSync().get.fetchStatus shouldBe FetchStatus.SCHEDULED
      folderSourceSqlDao.delSourceForUser(userId, source1.id).unsafeRunSync()
      sourceSqlDao.pauseNotInFolderSources().unsafeRunSync()
      sourceSqlDao.get(source1.id).unsafeRunSync().get.fetchStatus shouldBe FetchStatus.PAUSED

      sourceSqlDao.get(source3.id).unsafeRunSync().get.fetchStatus shouldBe FetchStatus.SCHEDULED
      folderSourceSqlDao.delSourceForUser(userId, source3.id).unsafeRunSync()
      sourceSqlDao.pauseNotInFolderSources().unsafeRunSync()
      sourceSqlDao.get(source3.id).unsafeRunSync().get.fetchStatus shouldBe FetchStatus.PAUSED
    }

  }

}
