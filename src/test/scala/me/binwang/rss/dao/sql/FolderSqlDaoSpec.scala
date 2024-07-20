package me.binwang.rss.dao.sql

import cats.effect.unsafe.IORuntime
import me.binwang.rss.generator.ConnectionPoolManager.connectionPool
import me.binwang.rss.generator.Folders
import me.binwang.rss.model.{Folder, FolderUpdater}
import org.scalacheck.Gen
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class FolderSqlDaoSpec extends AnyFunSpec with BeforeAndAfterEach with BeforeAndAfterAll with Matchers {

  implicit val ioRuntime: IORuntime = IORuntime.global

  private val folderDao = new FolderSqlDao

  override def beforeAll(): Unit = {
    folderDao.dropTable().unsafeRunSync()
    folderDao.createTable().unsafeRunSync()
  }

  override def beforeEach(): Unit = {
    folderDao.deleteAll().unsafeRunSync()
  }

  describe("Folder SQL DAO") {

    it("should insert and get folders") {
      val userID = Gen.uuid.sample.get.toString
      val folder1 = Folders.get(userID, 0)
      val folder2 = Folders.get(userID, 1)
      folderDao.insertIfNotExist(folder1).unsafeRunSync() shouldBe true
      folderDao.insertIfNotExist(folder2).unsafeRunSync() shouldBe true
      folderDao.insertIfNotExist(folder2).unsafeRunSync() shouldBe false

      val folders = folderDao.listByUser(userID).compile.toList.unsafeRunSync()
      folders.length shouldBe 2
      folders.head shouldBe folder1
      folders(1) shouldBe folder2

      folderDao.getByID(folder1.id).unsafeRunSync().get shouldBe folder1
      folderDao.listByUser(userID, 1, 1).compile.toList.unsafeRunSync().size shouldBe 1
      folderDao.listByUser(userID, 1, 2).compile.toList.unsafeRunSync().size shouldBe 0
    }

    it("should not insert folder if name is duplicated") {
      val userID = Gen.uuid.sample.get.toString
      val folder1 = Folders.get(userID, 0)
      val folderGen = Folders.get(userID, 1)
      val folder2 = Folder(
        id = folderGen.id,
        name = folder1.name,
        userID = userID,
        description = folderGen.description,
        position = 1,
        count = 0
      )

      folderDao.insertIfNotExist(folder1).unsafeRunSync() shouldBe true
      folderDao.insertIfNotExist(folder2).unsafeRunSync() shouldBe false

      val folders = folderDao.listByUser(userID).compile.toList.unsafeRunSync()
      folders.length shouldBe 1
      folders.head shouldBe folder1
    }

    it("should update folder") {
      val userID = Gen.uuid.sample.get.toString
      val folder1 = Folders.get(userID, 0)
      val folder2 = Folders.get(userID, 1)
      folderDao.insertIfNotExist(folder1).unsafeRunSync() shouldBe true
      folderDao.insertIfNotExist(folder2).unsafeRunSync() shouldBe true

      val folders = folderDao.listByUser(userID).compile.toList.unsafeRunSync()
      folders.length shouldBe 2
      folders.head shouldBe folder1
      folders(1) shouldBe folder2

      folderDao.update(folder1.id, FolderUpdater(Some("new_name"), Some(Some("new_desc")), Some(2)))
        .unsafeRunSync() shouldBe true
      val updatedFolders = folderDao.listByUser(userID).compile.toList.unsafeRunSync()
      updatedFolders.length shouldBe 2
      updatedFolders.head shouldBe folder2
      val updatedFolder1 = updatedFolders(1)
      updatedFolder1.id shouldBe folder1.id
      updatedFolder1.name shouldBe "new_name"
      updatedFolder1.description shouldBe Some("new_desc")
    }

    it("should delete folder") {
      val userID = Gen.uuid.sample.get.toString
      val folder1 = Folders.get(userID, 0)
      val folder2 = Folders.get(userID, 1)
      folderDao.insertIfNotExist(folder1).unsafeRunSync() shouldBe true
      folderDao.insertIfNotExist(folder2).unsafeRunSync() shouldBe true

      val folders = folderDao.listByUser(userID).compile.toList.unsafeRunSync()
      folders.length shouldBe 2
      folders.head shouldBe folder1
      folders(1) shouldBe folder2

      folderDao.delete(folder1.id).unsafeRunSync() shouldBe true
      val updatedFolders = folderDao.listByUser(userID).compile.toList.unsafeRunSync()
      updatedFolders.length shouldBe 1
      updatedFolders.head shouldBe folder2

      folderDao.delete(folder1.id).unsafeRunSync() shouldBe false
      val updatedFolders2 = folderDao.listByUser(userID).compile.toList.unsafeRunSync()
      updatedFolders2.length shouldBe 1
      updatedFolders2.head shouldBe folder2
    }

    it("should cleanup position") {
      val userID = Gen.uuid.sample.get.toString
      val userID2 = Gen.uuid.sample.get.toString
      val folder1 = Folders.get(userID, 0)
      val folderAnother = Folders.get(userID2, 1)
      val folder2 = Folders.get(userID, 2)
      folderDao.insertIfNotExist(folder1).unsafeRunSync() shouldBe true
      folderDao.insertIfNotExist(folderAnother).unsafeRunSync() shouldBe true
      folderDao.insertIfNotExist(folder2).unsafeRunSync() shouldBe true
      folderDao.cleanupPositionForUser(userID).unsafeRunSync()

      val folders = folderDao.listByUser(userID).compile.toList.unsafeRunSync()
      folders.length shouldBe 2
      folders.head.id shouldBe folder1.id
      folders(1).id shouldBe folder2.id
      folders.head.position shouldBe 0
      folders(1).position shouldBe 1000

      val user2folders = folderDao.listByUser(userID2).compile.toList.unsafeRunSync()
      user2folders.head.position shouldBe 1
    }

  }

}
