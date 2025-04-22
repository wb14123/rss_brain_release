package me.binwang.rss.model

import java.time.ZonedDateTime

/**
 * Verification code for deleting user data
 */
case class UserDeleteCode(
    code: String,
    userID: String,
    expireDate: ZonedDateTime,
)
