package me.binwang.rss.model

import java.time.ZonedDateTime

case class UserDeleteCode(
    code: String,
    userID: String,
    expireDate: ZonedDateTime,
)
