package me.binwang.rss.service

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import me.binwang.rss.dao.sql._
import me.binwang.rss.generator.ConnectionPoolManager.connectionPool
import me.binwang.rss.generator.Users
import me.binwang.rss.mail.MailSender
import me.binwang.rss.model.{UserSession, UserUpdater}
import me.binwang.rss.util.Throttler
import org.scalamock.scalatest.MockFactory
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.util.UUID

class ApplePaymentServiceSpec extends AnyFunSpec with BeforeAndAfterEach with BeforeAndAfterAll
  with Matchers with MockFactory {

  implicit val ioRuntime: IORuntime = IORuntime.global
  implicit val loggerFactory: LoggerFactory[IO] = Slf4jFactory.create[IO]
  private implicit val userSessionDao: UserSessionSqlDao = new UserSessionSqlDao()
  private implicit val folderDao: FolderSqlDao = new FolderSqlDao()
  private val userDao: UserSqlDao = new UserSqlDao()
  private val sourceDao: SourceSqlDao = new SourceSqlDao()
  private val authorizer: Authorizer = new Authorizer(Throttler(), userSessionDao, folderDao)
  private val paymentCustomerDao: PaymentCustomerSqlDao = new PaymentCustomerSqlDao()
  private val mailSender = mock[MailSender]

  private val token = UUID.randomUUID().toString
  private val email = "test@example.com"
  private val user = Users.get(email)
  private val userID = user.id

  private val applePaymentService = new ApplePaymentService(userDao, userSessionDao, paymentCustomerDao, sourceDao,
    authorizer, mailSender)

  override def beforeAll(): Unit = {
    val createTables = for {
      _ <- userSessionDao.dropTable()
      _ <- userSessionDao.createTable()
      _ <- folderDao.dropTable()
      _ <- folderDao.createTable()
      _ <- userDao.dropTable()
      _ <- userDao.createTable()
      _ <- paymentCustomerDao.dropTable()
      _ <- paymentCustomerDao.createTable()
      _ <- sourceDao.dropTable()
      _ <- sourceDao.createTable()
    } yield ()
    createTables.unsafeRunSync()
  }

  override def beforeEach(): Unit = {
    val clearTables = for {
      _ <- userSessionDao.deleteAll()
      _ <- folderDao.deleteAll()
      _ <- userDao.deleteAll()
      _ <- paymentCustomerDao.deleteAll()
      _ <- sourceDao.deleteAll()
    } yield ()
    clearTables.unsafeRunSync()

    userDao.insertIfNotExists(user).unsafeRunSync()
    userSessionDao.insert(UserSession(token, userID, ZonedDateTime.now.plusDays(1), isAdmin = false,
      ZonedDateTime.now.plusDays(1))).unsafeRunSync()
  }


  describe("App payment service") {
    it("should handle init payment succeed callback") {
      val startTime = ZonedDateTime.now.withYear(1998)
      val randomCustomerID = "c0250d4b-e6f6-4122-8177-a671e2f73e84"
      val (customerID, user) = applePaymentService.getCustomerID(token, Some(_ => IO(randomCustomerID))).unsafeRunSync()
      customerID shouldBe randomCustomerID
      user.id shouldBe userID
      val responseBody = new String(getClass.getResourceAsStream("/apple-payment-callback-init-pay.json").readAllBytes(),
        StandardCharsets.UTF_8)
      userDao.update(userID, UserUpdater(subscribeEndAt = Some(startTime))).unsafeRunSync()
      applePaymentService.inAppPurchaseCallback(responseBody, isSandBox = true).unsafeRunSync()
      userDao.getByID(userID).unsafeRunSync().get.subscribeEndAt.toInstant.toEpochMilli shouldBe 1668739291000L
    }
  }



}
