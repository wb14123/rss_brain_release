package me.binwang.rss.service

import cats.effect.IO
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.bc.BouncyCastleProviderSingleton
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.util.X509CertUtils
import io.circe.generic.extras._
import io.circe.parser
import me.binwang.rss.dao.{PaymentCustomerDao, SourceDao, UserDao, UserSessionDao}
import me.binwang.rss.mail.MailSender
import me.binwang.rss.service.ApplePaymentService._
import me.binwang.archmage.core.CatsMacros.timed
import me.binwang.rss.metric.TimeMetrics
import org.typelevel.log4cats.LoggerFactory

import java.time.{Instant, ZoneId, ZonedDateTime}
import java.util.UUID
import scala.jdk.CollectionConverters._

object ApplePaymentService {

  implicit val config: Configuration = Configuration.default.withDefaults

  @ConfiguredJsonCodec case class AppleResponseBodyV2(
    signedPayload: String,
  )

  @ConfiguredJsonCodec case class AppleResponseBodyV2DecodedPayload(
    notificationType: String,
    subtype: Option[String],
    data: ApplePayloadData,
  )

  @ConfiguredJsonCodec case class ApplePayloadData(
    bundleId: String,
    bundleVersion: String,
    environment: String,
    signedRenewalInfo: String,
    signedTransactionInfo: String,
  )

  @ConfiguredJsonCodec case class AppleJWSTransactionDecodedPayload(
    appAccountToken: String,
    bundleId: String,
    environment: String,
    expiresDate: Long, // timestamp in ms
    inAppOwnershipType: String,
    originalPurchaseDate: Long, // timestamp in ms
    originalTransactionId: String,
    productId: String,
    purchaseDate: Long, // timestamp in ms
    quantity: Int,
    transactionId: String,
    `type`: String,
    webOrderLineItemId: String,
  )
}

class ApplePaymentService(
    override val userDao: UserDao,
    override val userSessionDao: UserSessionDao,
    override val paymentCustomerDao: PaymentCustomerDao,
    override val sourceDao: SourceDao,
    override val authorizer: Authorizer,
    override val mailSender: MailSender,
)(implicit val loggerFactory: LoggerFactory[IO]) extends PaymentService with TimeMetrics {

  override val thirdParty: String = "APPLE_IN_APP"

  private val logger = LoggerFactory.getLoggerFromClass[IO](this.getClass)

  private val jcaProvider = BouncyCastleProviderSingleton.getInstance
  X509CertUtils.setProvider(jcaProvider)
  private val appleRootCa = X509CertUtils.parse(getClass.getResourceAsStream("/certs/AppleRootCA-G3.cer").readAllBytes())

  def getAppleCustomerID(token: String): IO[String] = timed {
    getCustomerID(token, Some(_ => IO.pure(UUID.randomUUID().toString))).map(_._1)
  }

  def inAppPurchaseCallback(responseBodyV2Str: String, isSandBox: Boolean): IO[Unit] = timed {
    logger.info(s"Get apple in app purchase callback, sandbox: $isSandBox, responseBodyV2: $responseBodyV2Str").flatMap {_ =>
      val responseBodyV2 = parser.parse(responseBodyV2Str.replace("\n", "")).flatMap(_.as[AppleResponseBodyV2]).toTry.get
      val jwsObject = JWSObject.parse(responseBodyV2.signedPayload)
      val jwsCerts = jwsObject.getHeader.getX509CertChain.asScala.map(c => X509CertUtils.parse(c.decode()))

      jwsCerts.sliding(2).foreach { x =>
        x.head.verify(x.last.getPublicKey)
      }
      jwsCerts.last.verify(appleRootCa.getPublicKey)

      val jwk = ECKey.parse(jwsCerts.head)
      val jwsVerifier = new ECDSAVerifier(jwk)
      if (!jwsObject.verify(jwsVerifier)) {
        throw new RuntimeException("Apple JWS object cannot be verified")
      }
      val responseBodyV2Payload = jwsObject.getPayload.toString
      val responseBodyV2DecodedPayload = parser.parse(responseBodyV2Payload).flatMap(_.as[AppleResponseBodyV2DecodedPayload]).toTry.get
      val transactionPayload = responseBodyV2DecodedPayload.data.signedTransactionInfo
      val transactionDecodedPayloadStr = JWSObject.parse(transactionPayload).getPayload.toString
      val transactionDecodedPayload = parser.parse(transactionDecodedPayloadStr).flatMap(_.as[AppleJWSTransactionDecodedPayload]).toTry.get

      val paymentSucceedNotifications = Seq("DID_RENEW", "SUBSCRIBED", "DID_CHANGE_RENEWAL_STATUS")
      if (paymentSucceedNotifications.contains(responseBodyV2DecodedPayload.notificationType)) {
        val subscribeEndTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(transactionDecodedPayload.expiresDate), ZoneId.systemDefault())
        val customerID = transactionDecodedPayload.appAccountToken
        paymentSucceed(customerID, subscribeEndTime, s"apple in app purchase successful, " +
          s"transaction id: ${transactionDecodedPayload.transactionId}, " +
          s"notification type: ${responseBodyV2DecodedPayload.notificationType}," +
          s"subtype: ${responseBodyV2DecodedPayload.subtype}"
        )
      } else if (responseBodyV2DecodedPayload.notificationType.equals("EXPIRED") &&
        responseBodyV2DecodedPayload.subtype.getOrElse("").equals("BILLING_RETRY")) {
        val customerID = transactionDecodedPayload.appAccountToken
        paymentFailed(customerID, s"apple in app purchase expired with payment retry failed ," +
          s"transaction id: ${transactionDecodedPayload.transactionId}, " +
          s"notification type: ${responseBodyV2DecodedPayload.notificationType}," +
          s"subtype: ${responseBodyV2DecodedPayload.subtype}"
        )
      } else {
        logger.info("Not handled Apple server notification, " +
          s"notification type: ${responseBodyV2DecodedPayload.notificationType}," +
          s"subtype: ${responseBodyV2DecodedPayload.subtype}"
        )
      }
    }
  }

}
