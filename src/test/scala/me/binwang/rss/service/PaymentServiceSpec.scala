package me.binwang.rss.service

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import me.binwang.rss.dao.sql.{FolderSqlDao, PaymentCustomerSqlDao, SourceSqlDao, UserSessionSqlDao, UserSqlDao}
import me.binwang.rss.dao.{PaymentCustomerDao, SourceDao, UserDao, UserSessionDao}
import me.binwang.rss.generator.ConnectionPoolManager.connectionPool
import me.binwang.rss.generator.Users
import me.binwang.rss.mail.MailSender
import me.binwang.rss.model.UserSession
import me.binwang.rss.util.Throttler
import org.scalamock.scalatest.MockFactory
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

import java.time.ZonedDateTime
import java.util.UUID

class PaymentServiceSpec extends AnyFunSpec with BeforeAndAfterEach with BeforeAndAfterAll
  with Matchers with MockFactory {

  implicit val ioRuntime: IORuntime = IORuntime.global
  implicit val globalLoggerFactory: LoggerFactory[IO] = Slf4jFactory.create[IO]
  private implicit val globalUserSessionDao: UserSessionSqlDao = new UserSessionSqlDao()
  private implicit val globalFolderDao: FolderSqlDao = new FolderSqlDao()
  private val globalUserDao: UserSqlDao = new UserSqlDao()
  private val globalAuthorizer: Authorizer = new Authorizer(Throttler(), globalUserSessionDao, globalFolderDao)
  private val globalPaymentCustomerDao: PaymentCustomerSqlDao = new PaymentCustomerSqlDao()
  private val globalMailSender = mock[MailSender]
  private val globalSourceDao: SourceSqlDao = new SourceSqlDao()

  private val token = UUID.randomUUID().toString
  private val email = "test@example.com"
  private val user = Users.get(email)
  private val userID = user.id

  class TestPaymentService extends PaymentService {
    override val userDao: UserDao = globalUserDao
    override val userSessionDao: UserSessionDao = globalUserSessionDao
    override val authorizer: Authorizer = globalAuthorizer
    override val paymentCustomerDao: PaymentCustomerDao = globalPaymentCustomerDao
    override val mailSender: MailSender = globalMailSender
    override val sourceDao: SourceDao = globalSourceDao
    override val thirdParty: String = "STRIPE"
    override implicit def loggerFactory: LoggerFactory[IO] = globalLoggerFactory
  }

  private val paymentService = new TestPaymentService()

  override def beforeAll(): Unit = {
    val createTables = for {
      _ <- globalUserSessionDao.dropTable()
      _ <- globalUserSessionDao.createTable()
      _ <- globalFolderDao.dropTable()
      _ <- globalFolderDao.createTable()
      _ <- globalUserDao.dropTable()
      _ <- globalUserDao.createTable()
      _ <- globalPaymentCustomerDao.dropTable()
      _ <- globalPaymentCustomerDao.createTable()
      _ <- globalSourceDao.dropTable()
      _ <- globalSourceDao.createTable()
    } yield ()
    createTables.unsafeRunSync()
  }

  override def beforeEach(): Unit = {
    val clearTables = for {
      _ <- globalUserSessionDao.deleteAll()
      _ <- globalFolderDao.deleteAll()
      _ <- globalUserDao.deleteAll()
      _ <- globalPaymentCustomerDao.deleteAll()
      _ <- globalSourceDao.deleteAll()
    } yield ()
    clearTables.unsafeRunSync()

    globalUserDao.insertIfNotExists(user).unsafeRunSync()
    globalUserSessionDao.insert(UserSession(token, userID, ZonedDateTime.now.plusDays(1), isAdmin = false,
      ZonedDateTime.now.plusDays(1))).unsafeRunSync()
  }

  describe("Payment service") {
    it("should handle payment succeed") {
      val endTime = ZonedDateTime.now.plusDays(30)
      val randomCustomerID = UUID.randomUUID().toString
      val (customerID, user) = paymentService.getCustomerID(token, Some(_ => IO(randomCustomerID))).unsafeRunSync()
      user.id shouldBe userID
      paymentService.paymentSucceed(customerID, endTime, "test comment").unsafeRunSync()
      globalUserDao.getByID(userID).unsafeRunSync().get.subscribeEndAt.toInstant.getEpochSecond shouldBe endTime.toInstant.getEpochSecond
    }
  }

}
