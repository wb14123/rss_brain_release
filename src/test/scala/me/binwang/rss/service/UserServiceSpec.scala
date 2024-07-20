package me.binwang.rss.service

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import me.binwang.rss.dao.sql._
import me.binwang.rss.generator.ConnectionPoolManager.connectionPool
import me.binwang.rss.mail.MailSender
import me.binwang.rss.model._
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory
// import me.binwang.rss.reddit.RedditGateway
// import me.binwang.rss.reddit.RedditModels.{RedditToken, RedditUser}
import me.binwang.rss.util.Throttler
// import me.binwang.rss.web.RedditService
import org.scalamock.scalatest.MockFactory
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime
import java.util.UUID

class UserServiceSpec extends AnyFunSpec with BeforeAndAfterEach with BeforeAndAfterAll with Matchers with MockFactory {


  implicit val ioRuntime: IORuntime = IORuntime.global
  implicit val loggerFactory: LoggerFactory[IO] = Slf4jFactory.create[IO]
  private implicit val userSessionDao: UserSessionSqlDao = new UserSessionSqlDao()
  private implicit val folderDao: FolderSqlDao = new FolderSqlDao()
  private implicit val userDao: UserSqlDao = new UserSqlDao()
  private implicit val redditSessionDao: RedditSessionSqlDao = new RedditSessionSqlDao()
  private implicit val passwordResetDao: PasswordResetSqlDao = new PasswordResetSqlDao()
  private implicit val paymentCustomerDao: PaymentCustomerSqlDao = new PaymentCustomerSqlDao()
  private implicit val userDeleteCodeDao: UserDeleteCodeSqlDao = new UserDeleteCodeSqlDao()
  private implicit val articleUserMarkingDao: ArticleUserMarkingSqlDao = new ArticleUserMarkingSqlDao()
  private implicit val sourceDao: SourceSqlDao = new SourceSqlDao()
  private implicit val folderSourceDao: FolderSourceSqlDao = new FolderSourceSqlDao()
  private implicit val moreLikeThisMappingDao: MoreLikeThisMappingSqlDao = new MoreLikeThisMappingSqlDao()
  private implicit val authorizer: Authorizer = new Authorizer(Throttler(), userSessionDao, folderDao)
  private val mailSender = mock[MailSender]
  private val userService = new UserService(userDao, userSessionDao, userDeleteCodeDao, articleUserMarkingDao,
    folderSourceDao, moreLikeThisMappingDao, folderDao, redditSessionDao, passwordResetDao,
    paymentCustomerDao, mailSender, authorizer, validateEmail = false)
  // private val redditGateway = mock[RedditGateway]
  // private val redditService = new RedditService(redditSessionDao, redditGateway)

  private val username = "username"
  private val password = "password"
  private val email = "abc.efg-1234@example.com"
  private val emailUpperCase = "ABC.EFG-1234@example.com"

  override def beforeAll(): Unit = {
    val createTables = for {
      _ <- userSessionDao.dropTable()
      _ <- userSessionDao.createTable()
      _ <- folderDao.dropTable()
      _ <- folderDao.createTable()
      _ <- userDao.dropTable()
      _ <- userDao.createTable()
      _ <- redditSessionDao.dropTable()
      _ <- redditSessionDao.createTable()
      _ <- passwordResetDao.dropTable()
      _ <- passwordResetDao.createTable()
      _ <- paymentCustomerDao.dropTable()
      _ <- paymentCustomerDao.createTable()
      _ <- userDeleteCodeDao.dropTable()
      _ <- userDeleteCodeDao.createTable()
      _ <- sourceDao.dropTable()
      _ <- sourceDao.createTable()
      _ <- folderSourceDao.dropTable()
      _ <- folderSourceDao.createTable()
      _ <- moreLikeThisMappingDao.dropTable()
      _ <- moreLikeThisMappingDao.createTable()
      _ <- articleUserMarkingDao.dropTable()
      _ <- articleUserMarkingDao.createTable()
    } yield ()
    createTables.unsafeRunSync()
  }

  override def beforeEach(): Unit = {
    val clearTables = for {
      _ <- userSessionDao.deleteAll()
      _ <- folderDao.deleteAll()
      _ <- userDao.deleteAll()
      _ <- redditSessionDao.deleteAll()
      _ <- passwordResetDao.deleteAll()
      _ <- paymentCustomerDao.deleteAll()
      _ <- userDeleteCodeDao.deleteAll()
      _ <- sourceDao.deleteAll()
      _ <- folderSourceDao.deleteAll()
      _ <- moreLikeThisMappingDao.deleteAll()
      _ <- articleUserMarkingDao.deleteAll()
    } yield ()
    clearTables.unsafeRunSync()
  }

  describe("User service") {

    it("should sign up user") {
      val userInfo = userService.signUp(username, password, email).unsafeRunSync()
      userInfo.username shouldBe username
      userInfo.email shouldBe email
    }

    it("should not sign up with the same email") {
      userService.signUp(username, password, email).unsafeRunSync()
      assertThrows[EmailAlreadyUsed] {
        userService.signUp(username, password, email).unsafeRunSync()
      }
    }

    it("should sign up with email verification and active account") {
      var activeLink: String = null
      (mailSender.sendActiveAccount _).expects(email, *).onCall { (_, link)  =>
        activeLink = link
        IO.unit
      }.once()

      val userService = new UserService(userDao, userSessionDao, userDeleteCodeDao, articleUserMarkingDao,
        folderSourceDao, moreLikeThisMappingDao, folderDao, redditSessionDao, passwordResetDao,
        paymentCustomerDao, mailSender, authorizer)
      val userInfo = userService.signUp(username, password, emailUpperCase).unsafeRunSync()
      userInfo.username shouldBe username
      userInfo.email shouldBe email

      assertThrows[UserNotActivated] {
        userService.login(email, password).unsafeRunSync()
      }

      val activeCode = activeLink.replace(UserService.activeAccountLinkPrefix, "")
      userService.activeAccount(activeCode).unsafeRunSync()

      userService.login(email, password).unsafeRunSync().userID shouldBe userInfo.id
      userService.login(emailUpperCase, password).unsafeRunSync().userID shouldBe userInfo.id

      assertThrows[InvalidActiveCodeException] {
        userService.activeAccount(activeCode).unsafeRunSync()
      }
    }

    it("should deny active if code is invalid") {
      var activeLink: String = null
      (mailSender.sendActiveAccount _).expects(email, *).onCall { (_, link)  =>
        activeLink = link
        IO.unit
      }.once()

      val userService = new UserService(userDao, userSessionDao, userDeleteCodeDao, articleUserMarkingDao,
        folderSourceDao, moreLikeThisMappingDao, folderDao, redditSessionDao, passwordResetDao,
        paymentCustomerDao, mailSender, authorizer)
      val userInfo = userService.signUp(username, password, email).unsafeRunSync()
      userInfo.username shouldBe username
      userInfo.email shouldBe email

      assertThrows[UserNotActivated] {
        userService.login(email, password).unsafeRunSync()
      }

      assertThrows[InvalidActiveCodeException] {
        userService.activeAccount("somecode").unsafeRunSync()
      }

      assertThrows[UserNotActivated] {
        userService.login(email, password).unsafeRunSync().userID shouldBe userInfo.id
      }
    }

    it("should not resend active code if user not found") {
      assertThrows[UserNotFound] {
        userService.resendActiveCode("abc@efg.com").unsafeRunSync()
      }
    }

    it("should resend active code") {
      var activeLink: String = null
      (mailSender.sendActiveAccount _).expects(email, *).onCall { (_, link)  =>
        activeLink = link
        IO.unit
      }.twice()

      val userService = new UserService(userDao, userSessionDao, userDeleteCodeDao, articleUserMarkingDao,
        folderSourceDao, moreLikeThisMappingDao, folderDao, redditSessionDao, passwordResetDao,
        paymentCustomerDao, mailSender, authorizer)
      val userInfo = userService.signUp(username, password, email).unsafeRunSync()
      userInfo.username shouldBe username
      userInfo.email shouldBe email

      assertThrows[UserNotActivated] {
        userService.login(email, password).unsafeRunSync()
      }

      val activeCode1 = activeLink.replace(UserService.activeAccountLinkPrefix, "")

      userService.resendActiveCode(email).unsafeRunSync()
      val activeCode2 = activeLink.replace(UserService.activeAccountLinkPrefix, "")

      activeCode1 shouldBe activeCode2

      userService.activeAccount(activeCode2).unsafeRunSync()

      userService.login(email, password).unsafeRunSync().userID shouldBe userInfo.id

      assertThrows[InvalidActiveCodeException] {
        userService.activeAccount(activeCode2).unsafeRunSync()
      }

      assertThrows[UserAlreadyActivated] {
        userService.resendActiveCode(email).unsafeRunSync()
      }
    }

    it("should be able to login after sign up") {
      val userInfo = userService.signUp(username, password, email).unsafeRunSync()
      userService.login(email, password).unsafeRunSync().userID shouldBe userInfo.id
    }

    it("should deny login if email or password is wrong") {
      userService.signUp(username, password, email).unsafeRunSync()
      assertThrows[LoginFail] {
        userService.login(email, "wrong_password").unsafeRunSync()
      }
      assertThrows[LoginFail] {
        userService.login("wrong_email", password).unsafeRunSync()
      }
    }

    it("should get user info after login") {
      val userInfo = userService.signUp(username, password, email).unsafeRunSync()
      val session = userService.login(email, password).unsafeRunSync()
      userService.getMyUserInfo(session.token).unsafeRunSync() shouldBe userInfo
    }

    it("should deny user if user is deleted") {
      val userInfo = userService.signUp(username, password, email).unsafeRunSync()
      val session = userService.login(email, password).unsafeRunSync()
      userDao.delete(userInfo.id).unsafeRunSync()
      assertThrows[UserNotAuthorized] {
        userService.getMyUserInfo(session.token).unsafeRunSync() shouldBe userInfo
      }
    }

    it("should deactivate user by admin") {
      val email2 = "email2@example.com"
      val userInfo1 = userService.signUp(username, password, email).unsafeRunSync()
      val userInfo2 = userService.signUp(username, password, email2).unsafeRunSync()
      userDao.update(userInfo1.id, UserUpdater(isAdmin = Some(true))).unsafeRunSync()

      val session2 = userService.login(email2, password).unsafeRunSync()

      val session = userService.login(email, password).unsafeRunSync()
      userService.deactivateUser(session.token, userInfo2.id).unsafeRunSync()

      assertThrows[UserNotAuthorized] {
        userService.getMyUserInfo(session2.token).unsafeRunSync()
      }
      assertThrows[UserNotActivated] {
        userService.login(email2, password).unsafeRunSync()
      }

      // user should not be able to active account again
      assertThrows[InvalidActiveCodeException] {
        userService.activeAccount("").unsafeRunSync()
      }

      assertThrows[UserCannotBeActivated] {
        userService.resendActiveCode(email2).unsafeRunSync()
      }
    }

    it("should throw error if deactivate user not found") {
      val userInfo1 = userService.signUp(username, password, email).unsafeRunSync()
      userDao.update(userInfo1.id, UserUpdater(isAdmin = Some(true))).unsafeRunSync()

      val session = userService.login(email, password).unsafeRunSync()
      assertThrows[UserNotFound] {
        userService.deactivateUser(session.token, UUID.randomUUID().toString).unsafeRunSync()
      }
    }

    it("should check admin permission") {
      val email2 = "email2@example.com"
      userService.signUp(username, password, email).unsafeRunSync()
      val userInfo2 = userService.signUp(username, password, email2).unsafeRunSync()
      val session = userService.login(email, password).unsafeRunSync()
      assertThrows[UserIsNotAdmin] {
        userService.deactivateUser(session.token, userInfo2.id).unsafeRunSync()
      }
    }

    it("should remove admin permission") {
      val userInfo1 = userService.signUp(username, password, email).unsafeRunSync()
      userDao.update(userInfo1.id, UserUpdater(isAdmin = Some(true))).unsafeRunSync()

      val session = userService.login(email, password).unsafeRunSync()
      userService.removeAdmin(session.token, userInfo1.id).unsafeRunSync()
      assertThrows[UserNotAuthorized] {
        userService.deactivateUser(session.token, userInfo1.id).unsafeRunSync()
      }

      val session2 = userService.login(email, password).unsafeRunSync()
      assertThrows[UserIsNotAdmin] {
        userService.deactivateUser(session2.token, userInfo1.id).unsafeRunSync()
      }
    }

    /*
    it("should authorize reddit user") {
      userService.signUp(username, password, email).unsafeRunSync()
      val session = userService.login(email, password).unsafeRunSync()
      val redditCode1 = UUID.randomUUID().toString
      val redditCode2 = UUID.randomUUID().toString
      val redditToken1 = UUID.randomUUID().toString
      val redditToken2 = UUID.randomUUID().toString

      (redditGateway.getAccessToken _).expects(redditCode1).returning(IO.pure(
        RedditToken(accessToken = redditToken1, expiresIn = 3600, scope = "", refreshToken = ""))).once()
      (redditGateway.getMe _).expects(redditToken1).returning(IO.pure(RedditUser("id1", "name1"))).once()
      var state = userService.createRedditSession(session.token).unsafeRunSync()
      redditService.authCallback(redditCode1, state).unsafeRunSync()
      var redditSessions = userService.getRedditSessions(session.token).compile.toList.unsafeRunSync()
      redditSessions.size shouldBe 1
      redditSessions.head.redditUserID shouldBe "id1"
      redditSessions.head.redditUserName.get shouldBe "name1"

      (redditGateway.getAccessToken _).expects(redditCode2).returning(IO.pure(
        RedditToken(accessToken = redditToken2, expiresIn = 3600, scope = "", refreshToken = ""))).once()
      (redditGateway.getMe _).expects(redditToken2).returning(IO.pure(RedditUser("id2", "name2"))).once()
      state = userService.createRedditSession(session.token).unsafeRunSync()
      redditService.authCallback(redditCode2, state).unsafeRunSync()
      redditSessions = userService.getRedditSessions(session.token).compile.toList.unsafeRunSync()
      redditSessions.size shouldBe 2
      redditSessions(1).redditUserID shouldBe "id2"
      redditSessions(1).redditUserName.get shouldBe "name2"
    }
     */

    it("should reset password") {
      val newPassword = "newPassword"
      var resetLink: String = null
      (mailSender.sendResetPassword _).expects(email, *, *).onCall { (_, link, _)  =>
        resetLink = link
        IO.unit
      }.once()
      val userInfo = userService.signUp(username, password, email).unsafeRunSync()
      userService.requestResetPassword(email).unsafeRunSync()
      val resetToken = resetLink.replace(UserService.resetPasswordLinkPrefix, "")
      userService.resetPassword(resetToken, newPassword).unsafeRunSync()
      userService.login(email, newPassword).unsafeRunSync().userID shouldBe userInfo.id
    }

    it("should use the same token if request reset password multiple times") {
      var resetLink: String = null
      (mailSender.sendResetPassword _).expects(email, *, *).onCall { (_, link, _)  =>
        resetLink = link
        IO.unit
      }.twice()
      userService.signUp(username, password, email).unsafeRunSync()
      userService.requestResetPassword(email).unsafeRunSync()
      val link1 = resetLink
      userService.requestResetPassword(email).unsafeRunSync()
      val link2 = resetLink
      link1 shouldBe link2
    }

    it("should use give a different password reset token if exiting token expires") {
      var resetLink: String = null
      (mailSender.sendResetPassword _).expects(email, *, *).onCall { (_, link, _) =>
        resetLink = link
        IO.unit
      }.twice()

      // use existing token if it's not expired
      val userInfo = userService.signUp(username, password, email).unsafeRunSync()
      val token1 = UUID.randomUUID().toString
      passwordResetDao.insert(PasswordReset(
        userID = userInfo.id, token = token1, expireTime = ZonedDateTime.now().plusHours(1)
      )).unsafeRunSync()
      userService.requestResetPassword(email).unsafeRunSync()
      resetLink.contains(token1) shouldBe true

      // cleanup data
      passwordResetDao.dropTable().unsafeRunSync()
      passwordResetDao.createTable().unsafeRunSync()

      // use a new token if it's expired
      val token2 = UUID.randomUUID().toString
      passwordResetDao.insert(PasswordReset(
        userID = userInfo.id, token = token2, expireTime = ZonedDateTime.now()
      )).unsafeRunSync()
      userService.requestResetPassword(email).unsafeRunSync()
      resetLink.contains(token2) shouldBe false
    }

    it("should deny password reset if token is expired") {
      val userService = new UserService(userDao, userSessionDao, userDeleteCodeDao, articleUserMarkingDao,
        folderSourceDao, moreLikeThisMappingDao, folderDao, redditSessionDao, passwordResetDao,
        paymentCustomerDao, mailSender, authorizer, resetTokenExpireTimeInHours = 0, validateEmail = false)
      val newPassword = "newPassword"
      var resetLink: String = null
      (mailSender.sendResetPassword _).expects(email, *, *).onCall { (_, link, _)  =>
        resetLink = link
        IO.unit
      }.once()
      userService.signUp(username, password, email).unsafeRunSync()
      userService.requestResetPassword(email).unsafeRunSync()
      val resetToken = resetLink.replace(UserService.resetPasswordLinkPrefix, "")
      assertThrows[PasswordResetInvalidException] {
        userService.resetPassword(resetToken, newPassword).unsafeRunSync()
      }
    }

    it("should deny password reset if token is already used") {
      val newPassword = "newPassword"
      var resetLink: String = null
      (mailSender.sendResetPassword _).expects(email, *, *).onCall { (_, link, _)  =>
        resetLink = link
        IO.unit
      }.once()
      val userInfo = userService.signUp(username, password, email).unsafeRunSync()
      userService.requestResetPassword(email).unsafeRunSync()
      val resetToken = resetLink.replace(UserService.resetPasswordLinkPrefix, "")
      userService.resetPassword(resetToken, newPassword).unsafeRunSync()
      userService.login(email, newPassword).unsafeRunSync().userID shouldBe userInfo.id
      assertThrows[PasswordResetInvalidException] {
        userService.resetPassword(resetToken, newPassword).unsafeRunSync()
      }
    }

    it("should get payment info") {
      val userInfo = userService.signUp(username, password, email).unsafeRunSync()
      val session = userService.login(email, password).unsafeRunSync()
      val customerID1 = UUID.randomUUID().toString
      val customerID2 = UUID.randomUUID().toString
      paymentCustomerDao.insert(PaymentCustomer(userInfo.id, "STRIPE", customerID1, ZonedDateTime.now())).unsafeRunSync()
      paymentCustomerDao.insert(PaymentCustomer(userInfo.id, "APPLE", customerID2, ZonedDateTime.now())).unsafeRunSync()
      val customers = userService.getPaymentCustomers(session.token).compile.toList.unsafeRunSync()
      customers.size shouldBe 2
    }

    it("should deny user delete if email and password is not correct") {
      val code = "abc"
      val userInfo = userService.signUp(username, password, email).unsafeRunSync()
      userDeleteCodeDao.insert(UserDeleteCode(code, userInfo.id, ZonedDateTime.now().plusDays(1))).unsafeRunSync()
      assertThrows[LoginFail] {
        userService.deleteUserData(email, "wrong_password", code).unsafeRunSync()
      }
    }

    it("should deny user delete if code is not correct") {
      val code = "abc"
      val userInfo = userService.signUp(username, password, email).unsafeRunSync()
      userDeleteCodeDao.insert(UserDeleteCode(code, userInfo.id, ZonedDateTime.now().plusDays(1))).unsafeRunSync()
      assertThrows[UserDeleteCodeInvalidException] {
        userService.deleteUserData(email, password, "wrong-code").unsafeRunSync()
      }
    }

    it("should deny user delete if code is expired") {
      val code = "abc"
      val userInfo = userService.signUp(username, password, email).unsafeRunSync()
      userDeleteCodeDao.insert(UserDeleteCode(code, userInfo.id, ZonedDateTime.now().plusDays(-1))).unsafeRunSync()
      assertThrows[UserDeleteCodeInvalidException] {
        userService.deleteUserData(email, password, code).unsafeRunSync()
      }
    }

    it("should delete user data") {
      val code = "abc"
      val userInfo = userService.signUp(username, password, email).unsafeRunSync()

      // check data exists before delete
      val userSession = userService.login(email, password).unsafeRunSync()
      userSessionDao.get(userSession.token).unsafeRunSync().isEmpty shouldBe false
      folderDao.listByUser(userInfo.id).compile.toList.unsafeRunSync().isEmpty shouldBe false

      userDeleteCodeDao.insert(UserDeleteCode(code, userInfo.id, ZonedDateTime.now().plusDays(1))).unsafeRunSync()
      userService.deleteUserData(email, password, code).unsafeRunSync()

      // check user data is deleted
      assertThrows[LoginFail] {
        userService.login(email, password).unsafeRunSync()
      }
      folderDao.listByUser(userInfo.id).compile.toList.unsafeRunSync().isEmpty shouldBe true
      userSessionDao.get(userSession.token).unsafeRunSync().isEmpty shouldBe true
      // TODO: more checks on other tables
    }

    it("should delete user data with Email sent") {
      val userInfo = userService.signUp(username, password, email).unsafeRunSync()
      // check data exists before delete
      val userSession = userService.login(email, password).unsafeRunSync()
      userSessionDao.get(userSession.token).unsafeRunSync().isEmpty shouldBe false
      folderDao.listByUser(userInfo.id).compile.toList.unsafeRunSync().isEmpty shouldBe false

      var deleteLink: String = ""
      (mailSender.sendUserDeleteConfirm _).expects(email, *).onCall { (_, link) =>
        deleteLink = link
        IO.unit
      }.once()

      userService.sendDeleteUserCode(userSession.token).unsafeRunSync()
      userService.deleteUserData(email, password, deleteLink.replace(UserService.deleteCodeLinkPrefix, "")).unsafeRunSync()

      // check user data is deleted
      assertThrows[LoginFail] {
        userService.login(email, password).unsafeRunSync()
      }
      folderDao.listByUser(userInfo.id).compile.toList.unsafeRunSync().isEmpty shouldBe true
      userSessionDao.get(userSession.token).unsafeRunSync().isEmpty shouldBe true
      // TODO: more checks on other tables
    }

    it("should not let user signup again if Email is already used and activated") {
      var activeLink: String = null
      (mailSender.sendActiveAccount _).expects(email, *).onCall { (_, link)  =>
        activeLink = link
        IO.unit
      }.once()

      val userService = new UserService(userDao, userSessionDao, userDeleteCodeDao, articleUserMarkingDao,
        folderSourceDao, moreLikeThisMappingDao, folderDao, redditSessionDao, passwordResetDao,
        paymentCustomerDao, mailSender, authorizer)
      val userInfo = userService.signUp(username, password, emailUpperCase).unsafeRunSync()
      userInfo.username shouldBe username
      userInfo.email shouldBe email

      assertThrows[UserNotActivated] {
        userService.login(email, password).unsafeRunSync()
      }

      val activeCode = activeLink.replace(UserService.activeAccountLinkPrefix, "")
      userService.activeAccount(activeCode).unsafeRunSync()

      userService.login(email, password).unsafeRunSync().userID shouldBe userInfo.id
      userService.login(emailUpperCase, password).unsafeRunSync().userID shouldBe userInfo.id

      assertThrows[InvalidActiveCodeException] {
        userService.activeAccount(activeCode).unsafeRunSync()
      }


      val username2 = "username2"
      val password2 = "password2"
      assertThrows[EmailAlreadyUsed] {
        userService.signUp(username2, password2, emailUpperCase).unsafeRunSync()
      }

      // check user is still old user
      userService.login(email, password).unsafeRunSync().userID shouldBe userInfo.id
      assertThrows[LoginFail] {
        userService.login(email, password2).unsafeRunSync().userID shouldBe userInfo.id
      }
    }

    it("should let user signup again if Email is already used but not activated") {
      var activeLink: String = null
      (mailSender.sendActiveAccount _).expects(email, *).onCall { (_, link)  =>
        activeLink = link
        IO.unit
      }.twice()

      val userService = new UserService(userDao, userSessionDao, userDeleteCodeDao, articleUserMarkingDao,
        folderSourceDao, moreLikeThisMappingDao, folderDao, redditSessionDao, passwordResetDao,
        paymentCustomerDao, mailSender, authorizer)
      val userInfo = userService.signUp(username, password, emailUpperCase).unsafeRunSync()
      userInfo.username shouldBe username
      userInfo.email shouldBe email
      val activeCode = activeLink.replace(UserService.activeAccountLinkPrefix, "")

      assertThrows[UserNotActivated] {
        userService.login(email, password).unsafeRunSync()
      }

      val username2 = "username2"
      val password2 = "password2"
      val userInfo2 = userService.signUp(username2, password2, emailUpperCase).unsafeRunSync()
      userInfo2.username shouldBe username2

      val activeCode2 = activeLink.replace(UserService.activeAccountLinkPrefix, "")

      // should deny if user still uses old active code
      assertThrows[InvalidActiveCodeException] {
        userService.activeAccount(activeCode).unsafeRunSync()
      }

      userService.activeAccount(activeCode2).unsafeRunSync()

      // should deny if user still uses old credentials
      assertThrows[LoginFail] {
        userService.login(email, password).unsafeRunSync()
      }
      val session = userService.login(email, password2).unsafeRunSync()
      session.userID shouldBe userInfo.id
      session.userID shouldBe userInfo2.id
      val myUserInfo = userService.getMyUserInfo(session.token).unsafeRunSync()
      myUserInfo.username shouldBe username2

      assertThrows[InvalidActiveCodeException] {
        userService.activeAccount(activeCode).unsafeRunSync()
      }
    }

  }
}
