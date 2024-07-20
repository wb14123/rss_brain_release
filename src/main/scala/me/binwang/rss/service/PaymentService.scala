package me.binwang.rss.service

import cats.effect.IO
import cats.effect.kernel.Clock
import com.typesafe.scalalogging.Logger
import me.binwang.rss.dao.{PaymentCustomerDao, SourceDao, UserDao, UserSessionDao}
import me.binwang.rss.mail.MailSender
import me.binwang.rss.model.{PaymentCustomer, PaymentCustomerNotFound, SubscriptionExpireDateTooEarly, User, UserNotFound, UserUpdater}
import org.typelevel.log4cats.LoggerFactory

import java.time.{ZoneId, ZonedDateTime}

trait PaymentService {

  val userDao: UserDao
  val userSessionDao: UserSessionDao
  val authorizer: Authorizer
  val paymentCustomerDao: PaymentCustomerDao
  val mailSender: MailSender
  val thirdParty: String
  val sourceDao: SourceDao

  implicit def loggerFactory: LoggerFactory[IO]
  private val logger = LoggerFactory.getLoggerFromClass(this.getClass)

  def paymentSucceed(customerID: String, subscribeEndTime: ZonedDateTime, comment: String): IO[Unit] = {
    paymentCustomerDao.getByCustomerID(customerID, thirdParty).flatMap {
      case None => logger.error(s"User paid successful but cannot find user ID for customer $customerID with $thirdParty")
      case Some(customer) =>
        val userID = customer.userID
        userDao.getByID(userID).flatMap {
          case None => IO.raiseError(UserNotFound(userID))
          case Some(user) if user.subscribeEndAt.isAfter(subscribeEndTime) =>
            IO.raiseError(SubscriptionExpireDateTooEarly(userID, customerID, thirdParty,
              expireDate = subscribeEndTime, userSubscriptionExpireDate = user.subscribeEndAt))
          case Some(_) =>
            for {
              _ <- userDao.update(userID, UserUpdater(subscribeEndAt = Some(subscribeEndTime), subscribed = Some(true)))
              _ <- userSessionDao.updateSubscribeEndTime(userID, subscribeEndTime)
              resumedCount <- sourceDao.resumeSourcesForUser(userID)
              _ <- logger.info(s"User paid, user ID: $userID, subscribeEndTime: $subscribeEndTime, " +
                s"comment: $comment, resumed sources: $resumedCount")
            } yield ()
        }
    }
  }

  def paymentFailed(customerID: String, comment: String): IO[Unit] = {
    paymentCustomerDao.getByCustomerID(customerID, thirdParty).flatMap {
      case None => logger.error(s"User failed to pay but cannot find user ID for customer $customerID with $thirdParty")
      case Some(customer) =>
        val userID = customer.userID
        for {
          user <- userDao.getByID(userID)
          _ <- mailSender.sendPaymentFailed(user.get.email)
          _ <- logger.warn(s"User failed to pay, user ID: $userID, comment: $comment")
        } yield ()
    }
  }


  /**
   *
   * @param token User token
   * @param createFunc If it's defined, use this function to get a customer ID and insert it to PaymentCustomerDao
   * @return (customerID, user)
   */
  def getCustomerID(token: String, createFunc: Option[User => IO[String]] = None): IO[(String, User)] = {
    authorizer.authorize(token, allowNotPaid = true).flatMap { session =>
      Clock[IO].realTimeInstant.flatMap { nowInstant =>
        val now = ZonedDateTime.ofInstant(nowInstant, ZoneId.systemDefault())
        paymentCustomerDao.getByUserID(session.userID, thirdParty).flatMap {
          case Some(c) =>
            userDao.getByID(session.userID).map {
              case None => throw new RuntimeException(s"User not found: ${session.userID}")
              case Some(user) => (c.customerID, user)
            }
          case None if createFunc.isDefined =>
            for {
              user <- userDao.getByID(session.userID).map {
                case None => throw new RuntimeException(s"User not found: ${session.userID}")
                case Some(user) => user
              }
              customerID <- createFunc.get.apply(user)
              inserted <- paymentCustomerDao.insert(PaymentCustomer(
                userID = session.userID, customerID = customerID, thirdParty = thirdParty, createdAt = now))
              _ <- if (!inserted) IO.raiseError(new RuntimeException("Insert payment customer failed")) else IO.unit
            } yield (customerID, user)
          case None =>
            IO.raiseError(PaymentCustomerNotFound(session.userID))
        }
      }
    }
  }

}
