package me.binwang.rss.model

import java.time.ZonedDateTime

case class PasswordReset(
    userID: String,
    token: String,
    expireTime: ZonedDateTime,
)
