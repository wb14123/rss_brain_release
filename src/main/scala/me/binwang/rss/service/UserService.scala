package me.binwang.rss.service

import cats.effect.{Clock, IO}
import cats.implicits._
import com.typesafe.config.ConfigFactory
import me.binwang.archmage.core.CatsMacros.timed
import me.binwang.rss.dao._
import me.binwang.rss.mail.MailSender
import me.binwang.rss.metric.TimeMetrics
import me.binwang.rss.model._
import me.binwang.rss.service.UserService._
import org.typelevel.log4cats.LoggerFactory

import java.security.MessageDigest
import java.time.{ZoneId, ZonedDateTime}
import java.util.UUID

object UserService {
  private val websiteBaseUrl = ConfigFactory.load().getString("website.baseUrl")
  val resetPasswordLinkPrefix = s"$websiteBaseUrl/reset-password/"
  val activeAccountLinkPrefix = s"$websiteBaseUrl/active?code="
  val deleteCodeLinkPrefix = s"$websiteBaseUrl/#/user-delete/"
}

class UserService(
  private val userDao: UserDao,
  private val userSessionDao: UserSessionDao,
  private val userDeleteCodeDao: UserDeleteCodeDao,
  private val articleUserMarkingDao: ArticleUserMarkingDao,
  private val folderSourceDao: FolderSourceDao,
  private val moreLikeThisMappingDao: MoreLikeThisMappingDao,
  private val folderDao: FolderDao,
  private val redditSessionDao: RedditSessionDao,
  private val passwordResetDao: PasswordResetDao,
  private val paymentCustomerDao: PaymentCustomerDao,
  private val mailSender: MailSender,
  private val authorizer: Authorizer,
  private val tokenExpireTimeInDays: Int = 7,
  private val resetTokenExpireTimeInHours: Int = 1,
  private val validateEmail: Boolean = true,
  private val freeTrailDays: Int = 7,
)(implicit val loggerFactory: LoggerFactory[IO]) extends TimeMetrics {

  private val logger = LoggerFactory.getLoggerFromClass[IO](this.getClass)

  def signUp(username: String, password: String, email: String): IO[UserInfo] = timed {
    val salt = UUID.randomUUID().toString
    val userID = UUID.randomUUID().toString
    val cleanEmail = email.toLowerCase.strip()
    val usePassword = passwordHash(password, salt)
    val activeCode = if (validateEmail) Some(UUID.randomUUID().toString) else None
    for {
      defaultFolderID <- generateDefaultFolder(userID)
      nowInstant <- Clock[IO].realTimeInstant
      now = ZonedDateTime.ofInstant(nowInstant, ZoneId.systemDefault()).withNano(0)
      subscribeEndAt = now.plusDays(freeTrailDays)
      user <- IO.pure(User(
        id = userID,
        username = username,
        password = usePassword,
        salt = salt,
        email = cleanEmail,
        defaultFolderID = defaultFolderID,
        lastLoginTime = None,
        lastLoginIP = None,
        isActive = !validateEmail,
        isAdmin = false,
        createdAt = now,
        activeCode = activeCode,
        subscribeEndAt = subscribeEndAt
      ))
      userInserted <- userDao.insertIfNotExists(user)
      /*
       Possible race condition here if the old user active at the same time.
       But shouldn't matter since the user is still updated with the new credential.

       Possible risk if there is data associated to old user. But this shouldn't matter since:

       1. The user shouldn't have data if it's still not activated.
       2. The data belongs to the user that has the same Email. If he has the Email, he can get the account anyway
          through "forgot password".
       */
      _ <- if (userInserted) IO.unit else {
        userDao.getByEmail(cleanEmail).flatMap{ existedUser =>
          if (existedUser.exists(_.isActive)) {
            IO.raiseError(EmailAlreadyUsed(email.toLowerCase))
          } else {
            userDao.update(existedUser.get.id,
              UserUpdater(
                username = Some(username),
                password = Some(usePassword),
                salt = Some(salt),
                isActive = Some(!validateEmail),
                activeCode = Some(activeCode),
                isAdmin = Some(false),
                subscribeEndAt = Some(subscribeEndAt),
              ))
          }
        }
      }
      _ <- if (!validateEmail) IO.unit else {
        mailSender.sendActiveAccount(email.toLowerCase, s"$activeAccountLinkPrefix${user.activeCode.get}")
      }
      finalUser <- userDao.getByEmail(cleanEmail)
    } yield finalUser.get.toInfo
  }

  def resendActiveCode(email: String): IO[Unit] = timed {
    userDao.getByEmail(email.toLowerCase).flatMap {
      case None => IO.raiseError(UserNotFound(email.toLowerCase))
      case Some(user) if user.isActive => IO.raiseError(UserAlreadyActivated(email.toLowerCase))
      case Some(user) if user.activeCode.isEmpty => IO.raiseError(UserCannotBeActivated(email.toLowerCase))
      case Some(user) =>
        mailSender.sendActiveAccount(email.toLowerCase, s"$activeAccountLinkPrefix${user.activeCode.get}")
    }
  }

  def activeAccount(activeCode: String): IO[Unit] = timed {
    if (activeCode.isBlank) {
      IO.raiseError(InvalidActiveCodeException(activeCode))
    } else {
      userDao.getByActiveCode(activeCode).flatMap {
        case None => IO.raiseError(InvalidActiveCodeException(activeCode))
        case Some(user) =>
          userDao.update(user.id, UserUpdater(isActive = Some(true), activeCode = Some(None))).map(_ => ())
      }
    }
  }

  def login(email: String, password: String): IO[UserSession] = timed {
    userDao.getByEmail(email.toLowerCase).flatMap {
      case Some(user) if !user.isActive && passwordHash(password, user.salt).equals(user.password) =>
        IO.raiseError(UserNotActivated(email.toLowerCase))
      case Some(user) if user.isActive && passwordHash(password, user.salt).equals(user.password) =>
        for {
          userSession <- IO(UserSession(
            token = UUID.randomUUID().toString,
            userID = user.id,
            expireTime = ZonedDateTime.now().withNano(0).plusDays(tokenExpireTimeInDays),
            isAdmin = user.isAdmin,
            subscribeEndTime = user.subscribeEndAt,
            subscribed = user.subscribed,
          ))
          _ <- userSessionDao.insert(userSession)
        } yield userSession
      case _ => IO.raiseError(LoginFail(email.toLowerCase))
    }
  }

  def requestResetPassword(email: String): IO[Unit] = timed {
    val validToken = userDao.getByEmail(email.toLowerCase).flatMap {
      case None => IO.raiseError(UserNotFound(email.toLowerCase))
      case Some(user) =>
        for {
          tokens <- passwordResetDao.listByUser(user.id).compile.toList
          nowInstant <- Clock[IO].realTimeInstant
          now = ZonedDateTime.ofInstant(nowInstant, ZoneId.systemDefault())
          _ <- tokens.filter(t => t.expireTime.isBefore(now) || t.expireTime.equals(now))
            .map { token => passwordResetDao.deleteByToken(token.token) }
            .sequence
          validToken <- tokens.find(_.expireTime.isAfter(now)) match {
            case None =>
              val newToken = PasswordReset(
                token = UUID.randomUUID().toString,
                userID = user.id,
                expireTime = ZonedDateTime.now().plusHours(resetTokenExpireTimeInHours)
              )
              passwordResetDao.insert(newToken).map(_ => newToken)
            case Some(token) => IO.pure(token)
          }
        } yield validToken
    }

    validToken.flatMap { token =>
      val link = s"$resetPasswordLinkPrefix${token.token}"
      val suffix = if (resetTokenExpireTimeInHours > 1) "hours" else "hour"
      val expireTimeStr = s"$resetTokenExpireTimeInHours $suffix"
      mailSender.sendResetPassword(email.toLowerCase, link, expireTimeStr)
    }
  }

  def resetPassword(resetToken: String, newPassword: String): IO[Unit] = timed {
    passwordResetDao.getByToken(resetToken).flatMap {
      case None => IO.raiseError(PasswordResetInvalidException(resetToken))
      case Some(session) if session.expireTime.isBefore(ZonedDateTime.now()) =>
        passwordResetDao.deleteByToken(resetToken).flatMap(
          _ => IO.raiseError(PasswordResetInvalidException(resetToken)))
      case Some(session) =>
        val salt = UUID.randomUUID().toString
        val password = passwordHash(newPassword, salt)
        for {
          _ <- userDao.update(session.userID, UserUpdater(password = Some(password), salt = Some(salt)))
          _ <- userSessionDao.deleteByUser(session.userID)
          _ <- passwordResetDao.deleteByToken(resetToken)
        } yield ()
    }
  }

  def signOut(token: String): IO[Boolean] = timed {
    userSessionDao.delete(token)
  }

  def signOutAllDevices(token: String): IO[Long] = timed {
    authorizer.authorize(token).flatMap { userSession =>
      userSessionDao.deleteByUser(userSession.userID)
    }
  }

  def getMyUserInfo(token: String): IO[UserInfo] = timed {
    authorizer
      .authorize(token, allowNotPaid = true)
      .flatMap(session => userDao.getByID(session.userID))
      .flatMap {
        // user not found, delete outdated user session
        case None => userSessionDao.delete(token).flatMap(_ => IO.raiseError(UserNotAuthorized(token)))
        case Some(user) => IO.pure(user.toInfo)
      }
  }

  def deactivateUser(token: String, userID: String): IO[Unit] = timed {
    adminUpdateUser(token, userID, UserUpdater(isActive = Some(false)))
  }

  def removeAdmin(token: String, userID: String): IO[Unit] = timed {
    adminUpdateUser(token, userID, UserUpdater(isAdmin = Some(false)))
  }

  def createRedditSession(token: String): IO[String] = timed {
    for {
      nowInstant <- Clock[IO].realTimeInstant
      now = ZonedDateTime.ofInstant(nowInstant, ZoneId.systemDefault())
      userSession <- authorizer.authorize(token)
      result <- redditSessionDao
        .getByUserID(userSession.userID)
        .filter(_.accessAcceptedAt.isEmpty)
        .take(1)
        .compile.toList
        .flatMap(_.headOption match {
          case Some(session) => IO.pure(session.state)
          case None =>
            val redditSession = RedditSession(userSession.userID, createdAt = now)
            redditSessionDao.insert(redditSession).map(_ => redditSession.state)
        })
    } yield result
  }

  def getRedditSessions(token: String): fs2.Stream[IO, RedditSession] = timed {
    authorizer.authorizeAsStream(token).flatMap { userSession =>
      redditSessionDao.getByUserID(userSession.userID).map(_.copy(token = None, refreshToken = None))
    }
  }

  def setCurrentFolder(token: String, currentFolderID: String): IO[Unit] = timed {
    authorizer.authorize(token).flatMap { userSession =>
      userDao
        .update(userSession.userID, UserUpdater(
          currentFolderID = Some(Some(currentFolderID)),
          currentSourceID = Some(None),
        ))
        .map(_ => ())
    }
  }

  def setCurrentSource(token: String, currentSourceID: String): IO[Unit] = timed {
    authorizer.authorize(token).flatMap { userSession =>
      userDao
        .update(userSession.userID, UserUpdater(
          currentFolderID = Some(None),
          currentSourceID = Some(Some(currentSourceID)),
        ))
        .map(_ => ())
    }
  }

  def removeCurrentFolderAndSource(token: String): IO[Unit] = timed {
    authorizer.authorize(token).flatMap { userSession =>
      userDao.update(userSession.userID, UserUpdater(currentFolderID = Some(None), currentSourceID = Some(None)))
    }.map(_ => ())
  }

  def getPaymentCustomers(token: String): fs2.Stream[IO, PaymentCustomer] = timed {
    authorizer.authorizeAsStream(token, allowNotePaid = true).flatMap { userSession =>
      paymentCustomerDao.listByUserID(userSession.userID)
    }
  }

  def sendDeleteUserCode(token: String): IO[Unit] = timed {

    for {
      nowInstant <- Clock[IO].realTimeInstant
      now = ZonedDateTime.ofInstant(nowInstant, ZoneId.systemDefault())
      userSession <- authorizer.authorize(token)
      _ <- userDao.getByID(userSession.userID).flatMap {
        case None => IO.raiseError(UserNotFound(userSession.userID))
        case Some(user) =>
          val code = UUID.randomUUID().toString
          userDeleteCodeDao
            .insert(UserDeleteCode(code = code, userID = user.id, expireDate = now.plusDays(tokenExpireTimeInDays)))
            .flatMap { _ =>
              val link = UserService.deleteCodeLinkPrefix + code
              mailSender.sendUserDeleteConfirm(user.email, link)
            }
      }
    } yield ()
  }

  def deleteUserData(email: String, password: String, verificationCode: String): IO[Unit] = timed {
    (for {
      userSession <- login(email, password)
      userDeleteCode <- userDeleteCodeDao.getByCode(verificationCode)
      nowInstant <- Clock[IO].realTimeInstant
      now = ZonedDateTime.ofInstant(nowInstant, ZoneId.systemDefault())
    } yield (userSession, userDeleteCode, now)).flatMap {
      case (_, None, _) => IO.raiseError(UserDeleteCodeInvalidException(verificationCode))
      case (userSession, Some(userDeleteCode), _) if userDeleteCode.userID != userSession.userID =>
        IO.raiseError(UserDeleteCodeInvalidException(verificationCode))
      case (_, Some(userDeleteCode), now) if userDeleteCode.expireDate.isBefore(now) =>
        userDeleteCodeDao.deleteByCode(verificationCode).flatMap {_ =>
          IO.raiseError(UserDeleteCodeInvalidException(verificationCode))
        }
      case (userSession, _, _) => deleteAllUserData(userSession.userID, verificationCode)
    }
  }

  private def deleteAllUserData(userID: String, code: String): IO[Unit] = {
    for {
      _ <- logger.info(s"Start to delete all user data for user $userID")
      moreLikeThisCount <- moreLikeThisMappingDao.deleteAllForUser(userID)
      _ <- logger.info(s"Deleted $moreLikeThisCount more like this mappings for user $userID")
      articleMarkingCount <- articleUserMarkingDao.deleteAllForUser(userID)
      _ <- logger.info(s"Deleted $articleMarkingCount article markings for user $userID")
      folderSourceCount <- folderSourceDao.deleteAllForUser(userID)
      _ <- logger.info(s"Deleted $folderSourceCount folder source mappings for user $userID")
      folderCount <- folderDao.deleteAllForUser(userID)
      _ <- logger.info(s"Deleted $folderCount folders for user $userID")
      _ <- userSessionDao.deleteByUser(userID)
      _ <- logger.info(s"Deleted user sessions for user $userID")
      _ <- userDao.delete(userID)
      _ <- logger.info(s"Deleted user data for user $userID")
      _ <- userDeleteCodeDao.deleteByCode(code)
      _ <- logger.info(s"Delete user delete code $code for user $userID")
    } yield ()
  }


  private def adminUpdateUser(token: String, userID: String, updater: UserUpdater): IO[Unit] = {
    authorizer
      .authorizeAdmin(token)
      .flatMap(_ => userDao.getByID(userID))
      .flatMap {
        case None => IO.raiseError(UserNotFound(userID))
        case Some(user) => for {
          _ <- userSessionDao.deleteByUser(userID) // delete user session first to make sure refresh user permission
          _ <- userDao.update(user.id, updater.copy(password = None)) // don't update password
        } yield ()
      }
  }

  private def passwordHash(password: String, salt: String): String = {
    val str = password + salt
    MessageDigest
      .getInstance("SHA-256")
      .digest(str.getBytes("UTF-8"))
      .map("%02x".format(_)).mkString
  }

  private def generateDefaultFolder(userID: String): IO[String] = {
    val folder = Folder(
      id = UUID.randomUUID().toString,
      userID = userID,
      name = "",
      description = Some("user default folder"),
      position = 0,
      count = 0,
      isUserDefault =  true
    )
    folderDao.insertIfNotExist(folder).map(_ => folder.id)
  }

}
