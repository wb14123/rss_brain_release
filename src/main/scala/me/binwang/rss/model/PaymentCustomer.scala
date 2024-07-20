package me.binwang.rss.model

import java.time.ZonedDateTime

object PaymentThirdParty {
  val APPLE = "APPLE"
  val STRIPE = "STRIPE"
}

case class PaymentCustomer(
    userID: String,
    thirdParty: String,
    customerID: String,
    createdAt: ZonedDateTime,
)
