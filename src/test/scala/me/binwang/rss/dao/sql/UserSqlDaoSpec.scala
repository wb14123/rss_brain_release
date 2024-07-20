package me.binwang.rss.dao.sql

import cats.effect.unsafe.IORuntime
import me.binwang.rss.generator.ConnectionPoolManager.connectionPool
import me.binwang.rss.generator.Users
import me.binwang.rss.model.UserUpdater
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class UserSqlDaoSpec extends AnyFunSpec with BeforeAndAfterEach with BeforeAndAfterAll with Matchers{

  implicit val ioRuntime: IORuntime = IORuntime.global

  private val userDao = new UserSqlDao

  override def beforeAll(): Unit = {
    userDao.dropTable().unsafeRunSync()
    userDao.createTable().unsafeRunSync()
  }

  override def beforeEach(): Unit = {
    userDao.deleteAll().unsafeRunSync()
  }

  describe("User SQL DAO") {

    it("should insert user and get by email") {
      val email = "abc@exmaple.com"
      val user = Users.get(email)
      userDao.insertIfNotExists(user).unsafeRunSync() shouldBe true
      userDao.getByEmail(email).unsafeRunSync().get shouldBe user
      userDao.getByEmail("random").unsafeRunSync() shouldBe None
    }

    it("should ignore the user with same email") {
      val email = "abc@exmaple.com"
      val user = Users.get(email)
      userDao.insertIfNotExists(user).unsafeRunSync() shouldBe true
      userDao.insertIfNotExists(user).unsafeRunSync() shouldBe false
      userDao.getByEmail(email).unsafeRunSync().get shouldBe user
    }

    it("should get user by user ID") {
      val email = "abc@exmaple.com"
      val user = Users.get(email)
      userDao.insertIfNotExists(user).unsafeRunSync() shouldBe true
      userDao.getByID(user.id).unsafeRunSync().get shouldBe user
      userDao.getByID("random").unsafeRunSync() shouldBe None
    }

    it("should update user") {
      val email = "abc@exmaple.com"
      val newEmail = "new@example.com"
      val user = Users.get(email)
      userDao.insertIfNotExists(user).unsafeRunSync() shouldBe true
      userDao.getByEmail(email).unsafeRunSync().get shouldBe user
      userDao.getByEmail(newEmail).unsafeRunSync() shouldBe None

      userDao.update("random_id", UserUpdater(email = Some(newEmail))).unsafeRunSync() shouldBe false
      userDao.update(user.id, UserUpdater(email = Some(newEmail))).unsafeRunSync() shouldBe true


      userDao.getByEmail(email).unsafeRunSync() shouldBe None
      userDao.getByEmail(newEmail).unsafeRunSync().get.id shouldBe user.id
    }

  }

}
