package me.binwang.rss.service

import cats.effect.IO
import cats.effect.kernel.Clock
import com.google.common.base.Charsets
import com.stripe.Stripe
import com.stripe.model.checkout.Session
import com.stripe.model.{Customer, Invoice}
import com.stripe.net.Webhook
import com.stripe.param.CustomerCreateParams
import com.stripe.param.checkout.SessionCreateParams
import com.typesafe.config.ConfigFactory
import me.binwang.archmage.core.CatsMacros.timed
import me.binwang.rss.dao.{PaymentCustomerDao, SourceDao, UserDao, UserSessionDao}
import me.binwang.rss.mail.MailSender
import me.binwang.rss.metric.TimeMetrics
import me.binwang.rss.model.User
import org.typelevel.log4cats.LoggerFactory

import java.net.URLEncoder
import java.time.{Instant, ZoneId, ZonedDateTime}

class StripePaymentService(
    override val userDao: UserDao,
    override val userSessionDao: UserSessionDao,
    override val paymentCustomerDao: PaymentCustomerDao,
    override val sourceDao: SourceDao,
    override val authorizer: Authorizer,
    override val mailSender: MailSender,
)(implicit val loggerFactory: LoggerFactory[IO]) extends PaymentService with TimeMetrics {

  private val logger = LoggerFactory.getLoggerFromClass[IO](this.getClass)
  private val config = ConfigFactory.load()
  override val thirdParty: String = "STRIPE"

  Stripe.apiKey = config.getString("payment.stripe.apiKey")
  private val endpointSecret = config.getString("payment.stripe.endpointSecret")

  def paymentCallback(payload: String, sigHeader: String): IO[Unit] = timed {
    logger.info(s"Stripe signature header: $sigHeader, sdk API version: ${Stripe.API_VERSION}").flatMap { _ =>
      val event = Webhook.constructEvent(payload, sigHeader, endpointSecret)
      val stripeObject = event.getDataObjectDeserializer.getObject.get()
      if (event.getType.equals("checkout.session.completed")) {
        val session = stripeObject.asInstanceOf[Session]
        logger.info(s"Get session complete event, id: ${session.getId}, customerID: ${session.getCustomer}," +
          s"expire: ${session.getExpiresAt}")
      } else if (event.getType.equals("invoice.paid")) {
        val invoice = stripeObject.asInstanceOf[Invoice]
        val customerID = invoice.getCustomer
        val subscribeEndTimestamp = invoice.getLines.getData.get(0).getPeriod.getEnd * 1000
        val subscribeEndTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(subscribeEndTimestamp), ZoneId.systemDefault())
        paymentSucceed(customerID, subscribeEndTime, s"invoice ${invoice.getId}")
      } else if (event.getType.equals("invoice.payment_failed")) {
        val invoice = stripeObject.asInstanceOf[Invoice]
        val customerID = invoice.getCustomer
        paymentFailed(customerID, s"invoice ID: ${invoice.getId}")
      } else {
        logger.info(s"Unhandled strip event: ${event.getType}")
      }
    }
  }

  def createCheckoutSession(token: String, successUrl: String, cancelUrl: String, needRedirect: Boolean): IO[String] = timed {
    val priceID = config.getString("payment.stripe.priceID")
    getCustomerID(token, Some(createCustomer)).flatMap { case (customerID, user) =>
      val paramBuilder = new SessionCreateParams.Builder()
        .setSuccessUrl(getCallbackUrl(successUrl, needRedirect))
        .setCancelUrl(getCallbackUrl(cancelUrl, needRedirect))
        .setCustomer(customerID)
        .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
        .addLineItem(new SessionCreateParams.LineItem.Builder()
          // For metered billing, do not pass quantity
          .setQuantity(1L)
          .setPrice(priceID)
          .build()
        )

      Clock[IO].realTimeInstant.flatMap { nowInstant =>
        val now = ZonedDateTime.ofInstant(nowInstant, ZoneId.systemDefault())
        val freeTrailEnd = if (user.subscribeEndAt.isAfter(now)) {
          // Stripe free trail end must be at least 2 days later, add 1 more hour to be safe
          if (user.subscribeEndAt.plusDays(-2).plusHours(-1).isBefore(now)) {
            Some(now.plusDays(2).plusHours(1))
          } else {
            Some(user.subscribeEndAt)
          }
        } else {
          None
        }
        if (freeTrailEnd.isDefined) {
          paramBuilder.setSubscriptionData(new SessionCreateParams.SubscriptionData.Builder()
            .setTrialEnd(freeTrailEnd.get.toInstant.getEpochSecond)
            .build()
          )
        }

        val sessionCreateParams = paramBuilder.build()
        IO(Session.create(sessionCreateParams).getUrl)
      }

    }
  }

  def createPortalLink(token: String, returnUrl: String, needRedirect: Boolean): IO[String] = timed {
    getCustomerID(token).flatMap { case (customerID, _) =>
      val params = new com.stripe.param.billingportal.SessionCreateParams.Builder()
        .setReturnUrl(getCallbackUrl(returnUrl, needRedirect))
        .setCustomer(customerID)
        .build()
      IO(com.stripe.model.billingportal.Session.create(params).getUrl)
    }
  }

  private def getCallbackUrl(url: String, needRedirect: Boolean) = {
    if (!needRedirect) {
      url
    } else {
      val encodedUrl = URLEncoder.encode(url, Charsets.UTF_8)
      s"https://www.rssbrain.com/redirect.html?url=$encodedUrl"
    }
  }

  private def createCustomer(user: User): IO[String] = {
    IO(Customer.create(CustomerCreateParams.builder()
      .setName(user.username)
      .setEmail(user.email)
      .build()).getId)
  }

}
