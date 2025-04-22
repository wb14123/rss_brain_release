package me.binwang.rss.model

import java.time.ZonedDateTime

/**
 * Token for password reset.
 */
case class PasswordReset(
    userID: String,
    token: String,
    expireTime: ZonedDateTime,
)
