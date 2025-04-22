package me.binwang.rss.model

import java.time.ZonedDateTime

case class PaymentCustomer(
    userID: String,
    thirdParty: String,
    customerID: String,
    createdAt: ZonedDateTime,
)
