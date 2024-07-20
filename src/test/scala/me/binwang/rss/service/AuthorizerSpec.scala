package me.binwang.rss.service

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import me.binwang.rss.dao.sql.{FolderSqlDao, UserSessionSqlDao}
import me.binwang.rss.generator.ConnectionPoolManager.connectionPool
import me.binwang.rss.generator.Folders
import me.binwang.rss.model._
import me.binwang.rss.util.Throttler
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

import java.time.ZonedDateTime
import java.util.UUID

class AuthorizerSpec extends AnyFunSpec with BeforeAndAfterEach with BeforeAndAfterAll with Matchers{


  implicit val ioRuntime: IORuntime = IORuntime.global
  implicit val loggerFactory: LoggerFactory[IO] = Slf4jFactory.create[IO]
  private implicit val userSessionDao: UserSessionSqlDao = new UserSessionSqlDao()
  private implicit val folderDao: FolderSqlDao = new FolderSqlDao()
  private val authorizer: Authorizer = new Authorizer(Throttler(), userSessionDao, folderDao)

  private val token = UUID.randomUUID().toString
  private val userID = UUID.randomUUID().toString

  override def beforeAll(): Unit = {
    val createTables = for {
      _ <- userSessionDao.dropTable()
      _ <- userSessionDao.createTable()
      _ <- folderDao.dropTable()
      _ <- folderDao.createTable()
    } yield ()
    createTables.unsafeRunSync()
  }

  override def beforeEach(): Unit = {
    val clearTables = for {
      _ <- userSessionDao.deleteAll()
      _ <- folderDao.deleteAll()
    } yield ()
    clearTables.unsafeRunSync()

    userSessionDao.insert(UserSession(token, userID, ZonedDateTime.now.plusDays(1), isAdmin = false,
      ZonedDateTime.now.plusDays(1))).unsafeRunSync()
  }

  describe("Authorizer") {

    it("should authorize user if there is session") {
      authorizer.authorize(token).unsafeRunSync().userID shouldBe userID
    }

    it("should throw not authorized error") {
      assertThrows[UserNotAuthorized] {
        authorizer.authorize("random_token").unsafeRunSync().userID shouldBe userID
      }
    }

    it("should authorize and return a stream") {
      val sessionList = authorizer.authorizeAsStream(token).compile.toList.unsafeRunSync()
      sessionList.length shouldBe 1
      sessionList.head.userID shouldBe userID
    }

    it("should authorize folder permission") {
      val folder = Folders.get(userID, 0)
      folderDao.insertIfNotExist(folder).unsafeRunSync()
      authorizer.checkFolderPermission(token, folder.id).unsafeRunSync()._1.userID shouldBe userID
      assertThrows[NoPermissionOnFolder] {
        authorizer.checkFolderPermission(token, "random_folder_id").unsafeRunSync()._1.userID shouldBe userID
      }
    }

    it("should authorize folders permission") {
      val folder = Folders.get(userID, 0)
      folderDao.insertIfNotExist(folder).unsafeRunSync()
      val folder2 = Folders.get(userID, 0)
      folderDao.insertIfNotExist(folder2).unsafeRunSync()
      val folder3 = Folders.get("another_user", 0)
      folderDao.insertIfNotExist(folder3).unsafeRunSync()
      authorizer.checkFoldersPermission(token, Seq(folder.id, folder2.id)).unsafeRunSync().userID shouldBe userID
      assertThrows[NoPermissionOnFolders] {
        authorizer.checkFoldersPermission(token, Seq(folder.id, "random_folder_id")).unsafeRunSync().userID shouldBe userID
      }
      assertThrows[NoPermissionOnFolders] {
        authorizer.checkFoldersPermission(token, Seq(folder.id, folder3.id)).unsafeRunSync().userID shouldBe userID
      }
    }

    it("should deny user if subscription ended") {
      val token2 = UUID.randomUUID().toString
      val userID2 = UUID.randomUUID().toString
      userSessionDao.insert(UserSession(token2, userID2, ZonedDateTime.now.plusDays(1), isAdmin = false,
        ZonedDateTime.now.plusDays(-1))).unsafeRunSync()
      assertThrows[UserSubscriptionEnded] {
        authorizer.authorize(token2).unsafeRunSync().userID shouldBe userID
      }
    }

    it("should throttle user requests") {
      val myAuthorizer: Authorizer = new Authorizer(
        new Throttler(2, 1000, 1, 1000, 1),
        userSessionDao, folderDao)
      myAuthorizer.authorize(token).unsafeRunSync()
      myAuthorizer.authorize(token).unsafeRunSync()
      assertThrows[RateLimitExceedError] {
        myAuthorizer.authorize(token).unsafeRunSync()
      }
    }


  }
}
