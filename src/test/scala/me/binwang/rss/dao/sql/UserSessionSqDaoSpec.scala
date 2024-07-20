package me.binwang.rss.dao.sql

import cats.effect.unsafe.IORuntime
import me.binwang.rss.generator.ConnectionPoolManager.connectionPool
import me.binwang.rss.model.UserSession
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime
import java.util.UUID

class UserSessionSqDaoSpec extends AnyFunSpec with BeforeAndAfterEach with BeforeAndAfterAll with Matchers {

  implicit val ioRuntime: IORuntime = IORuntime.global

  private val userSessionDao = new UserSessionSqlDao

  override def beforeAll(): Unit = {
    userSessionDao.dropTable().unsafeRunSync()
    userSessionDao.createTable().unsafeRunSync()
  }

  override def beforeEach(): Unit = {
    userSessionDao.deleteAll().unsafeRunSync()
  }

  describe("UserSession SQL DAO") {

    it("should insert user session and get by token") {
      val userID = UUID.randomUUID().toString
      val userSession = UserSession(token = UUID.randomUUID().toString, userID = userID,
        expireTime = ZonedDateTime.now.withNano(0).plusDays(1),
        subscribeEndTime = ZonedDateTime.now.withNano(0).plusDays(1)
      )
      userSessionDao.insert(userSession).unsafeRunSync() shouldBe true
      userSessionDao.get(userSession.token).unsafeRunSync().get shouldBe userSession
      userSessionDao.get("random_token").unsafeRunSync() shouldBe None
    }

    it("should expire the user session") {
      val userID = UUID.randomUUID().toString
      val userSession = UserSession(token = UUID.randomUUID().toString, userID = userID,
        expireTime = ZonedDateTime.now.withNano(0).plusSeconds(1),
        subscribeEndTime = ZonedDateTime.now.withNano(0).plusSeconds(1),
      )
      userSessionDao.insert(userSession).unsafeRunSync() shouldBe true
      Thread.sleep(2000)
      userSessionDao.get(userSession.token).unsafeRunSync() shouldBe None
    }

  }

}
