package me.binwang.rss.dao.sql

import cats.effect.unsafe.IORuntime
import me.binwang.rss.generator.{Sources, Users}
import me.binwang.rss.model.{FetchStatus, FolderSourceMapping, SourceID, SourceUpdater}
import me.binwang.rss.generator.ConnectionPoolManager.connectionPool
import org.scalacheck.Gen
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime

class SourceSqlDaoSpec extends AnyFunSpec with BeforeAndAfterEach with BeforeAndAfterAll with Matchers {

  implicit val ioRuntime: IORuntime = IORuntime.global

  private val sourceDao = new SourceSqlDao()
  private val userDao: UserSqlDao = new UserSqlDao()
  private val folderSourceDao: FolderSourceSqlDao = new FolderSourceSqlDao()

  override def beforeAll(): Unit = {
    sourceDao.dropTable().unsafeRunSync()
    sourceDao.createTable().unsafeRunSync()
    userDao.dropTable().unsafeRunSync()
    userDao.createTable().unsafeRunSync()
    folderSourceDao.dropTable().unsafeRunSync()
    folderSourceDao.createTable().unsafeRunSync()
  }


  override def beforeEach(): Unit = {
    sourceDao.deleteAll().unsafeRunSync()
    userDao.deleteAll().unsafeRunSync()
    folderSourceDao.deleteAll().unsafeRunSync()
  }

  describe("Source SQL DAO") {

    it("should insert and get source") {
      val source = Sources.get()
      sourceDao.insert(source).unsafeRunSync()
      val result = sourceDao.get(source.id).unsafeRunSync()
      result.isDefined shouldBe true
      result.get shouldBe source
    }

    it("should get none when no source found") {
      sourceDao.get("id").unsafeRunSync().isEmpty shouldBe true
    }

    it("should update source") {
      val source = Sources.get()
      val newTitle = "new title"
      val newHtml = "new html"
      sourceDao.insert(source).unsafeRunSync()
      sourceDao.update(source.id, SourceUpdater(
        title = Some(Some(newTitle)),
        htmlUrl = Some(Some(newHtml))
      )).unsafeRunSync()
      val result = sourceDao.get(source.id).unsafeRunSync()
      result.get.title.get shouldBe newTitle
      result.get.htmlUrl.get shouldBe newHtml
      result.get.description shouldBe source.description
    }

    it("should not update if source not found") {
      sourceDao.update("id", SourceUpdater(title=Some(Some("new title")))).unsafeRunSync() shouldBe false
    }

    it("should get fetch urls") {
      val totalSize = 20
      val validSize = 15
      val fetchSize = 10
      (totalSize > validSize && validSize > fetchSize) shouldBe true
      (fetchSize * 2 > validSize) shouldBe true
      val genTimeMinutes = Gen.choose[Int](10, 100)
      val validSources = (1 to validSize).map(_ => Sources.get(Some(ZonedDateTime.now().minusMinutes(genTimeMinutes.sample.get))))
      val invalidSources = (1 to totalSize - validSize).map(_ => Sources.get())
      val sources = validSources ++ invalidSources
      val ids = validSources.sortWith(_.fetchScheduledAt isBefore _.fetchScheduledAt).map(_.id).take(fetchSize)
      sources.foreach(s => sourceDao.insert(s).unsafeRunSync())
      val urls = sourceDao.getFetchURLs(fetchSize, ZonedDateTime.now()).compile.toList.unsafeRunSync()
      urls.size shouldBe fetchSize
      urls.foreach { url =>
        val id = SourceID(url)
        val source = sourceDao.get(id).unsafeRunSync()
        source.isDefined shouldBe true
        source.get.fetchStatus shouldBe FetchStatus.PENDING
        ids should contain (source.get.id)
      }
      val secondUrls = sourceDao.getFetchURLs(fetchSize, ZonedDateTime.now()).compile.toList.unsafeRunSync()
      secondUrls.size shouldBe validSize - fetchSize
      val thirdUrls = sourceDao.getFetchURLs(fetchSize, ZonedDateTime.now()).compile.toList.unsafeRunSync()
      thirdUrls.size shouldBe 0
    }

    it("should pause sources without any folder") {
      val source1 = Sources.get()
      val source2 = Sources.get()
      sourceDao.insert(source1).unsafeRunSync()
      sourceDao.insert(source2).unsafeRunSync()
      sourceDao.get(source1.id).unsafeRunSync().get.fetchStatus shouldBe FetchStatus.SCHEDULED
      sourceDao.get(source2.id).unsafeRunSync().get.fetchStatus shouldBe FetchStatus.SCHEDULED

      val folderId = Gen.uuid.sample.get.toString
      val userId = "user_id"
      folderSourceDao.addSourceToFolder(FolderSourceMapping(folderId, source1.id, userId, 0)).unsafeRunSync() shouldBe true

      sourceDao.pauseNotInFolderSources().unsafeRunSync()
      sourceDao.get(source1.id).unsafeRunSync().get.fetchStatus shouldBe FetchStatus.SCHEDULED
      sourceDao.get(source2.id).unsafeRunSync().get.fetchStatus shouldBe FetchStatus.PAUSED
    }

    it("should pause sources for deactivated user") {
      val source1 = Sources.get()
      val source2 = Sources.get()
      sourceDao.insert(source1).unsafeRunSync()
      sourceDao.insert(source2).unsafeRunSync()
      sourceDao.get(source1.id).unsafeRunSync().get.fetchStatus shouldBe FetchStatus.SCHEDULED
      sourceDao.get(source2.id).unsafeRunSync().get.fetchStatus shouldBe FetchStatus.SCHEDULED

      val folderId = Gen.uuid.sample.get.toString
      val userId = "user_id"
      val email = "abc@exmaple.com"
      val user = Users.get(email).copy(id = userId, isActive = false)
      userDao.insertIfNotExists(user).unsafeRunSync() shouldBe true
      folderSourceDao.addSourceToFolder(FolderSourceMapping(folderId, source1.id, userId, 0)).unsafeRunSync() shouldBe true

      sourceDao.pauseSourcesForDeactivatedUsers(ZonedDateTime.now()).unsafeRunSync()
      sourceDao.get(source1.id).unsafeRunSync().get.fetchStatus shouldBe FetchStatus.PAUSED
      sourceDao.get(source2.id).unsafeRunSync().get.fetchStatus shouldBe FetchStatus.SCHEDULED
    }

    it("should pause sources for subscription ended user") {
      val source1 = Sources.get()
      val source2 = Sources.get()
      sourceDao.insert(source1).unsafeRunSync()
      sourceDao.insert(source2).unsafeRunSync()
      sourceDao.get(source1.id).unsafeRunSync().get.fetchStatus shouldBe FetchStatus.SCHEDULED
      sourceDao.get(source2.id).unsafeRunSync().get.fetchStatus shouldBe FetchStatus.SCHEDULED

      val folderId = Gen.uuid.sample.get.toString
      val userId = "user_id"
      val email = "abc@exmaple.com"
      val user = Users.get(email).copy(id = userId, isActive = true, subscribeEndAt = ZonedDateTime.now().minusDays(1))
      userDao.insertIfNotExists(user).unsafeRunSync() shouldBe true
      folderSourceDao.addSourceToFolder(FolderSourceMapping(folderId, source1.id, userId, 0)).unsafeRunSync() shouldBe true

      sourceDao.pauseSourcesForDeactivatedUsers(ZonedDateTime.now()).unsafeRunSync()
      sourceDao.get(source1.id).unsafeRunSync().get.fetchStatus shouldBe FetchStatus.PAUSED
      sourceDao.get(source2.id).unsafeRunSync().get.fetchStatus shouldBe FetchStatus.SCHEDULED
    }

    it("should resume sources for user") {
      val source1 = Sources.get().copy(fetchStatus = FetchStatus.PAUSED)
      val source2 = Sources.get().copy(fetchStatus = FetchStatus.PAUSED)
      sourceDao.insert(source1).unsafeRunSync()
      sourceDao.insert(source2).unsafeRunSync()
      sourceDao.get(source1.id).unsafeRunSync().get.fetchStatus shouldBe FetchStatus.PAUSED
      sourceDao.get(source2.id).unsafeRunSync().get.fetchStatus shouldBe FetchStatus.PAUSED

      val folderId = Gen.uuid.sample.get.toString
      val userId = "user_id"
      val email = "abc@exmaple.com"
      val user = Users.get(email).copy(id = userId, isActive = true, subscribeEndAt = ZonedDateTime.now().minusDays(1))
      userDao.insertIfNotExists(user).unsafeRunSync() shouldBe true
      folderSourceDao.addSourceToFolder(FolderSourceMapping(folderId, source1.id, userId, 0)).unsafeRunSync() shouldBe true

      sourceDao.resumeSourcesForUser(userId).unsafeRunSync()
      sourceDao.get(source1.id).unsafeRunSync().get.fetchStatus shouldBe FetchStatus.SCHEDULED
      sourceDao.get(source2.id).unsafeRunSync().get.fetchStatus shouldBe FetchStatus.PAUSED
    }
  }
}
