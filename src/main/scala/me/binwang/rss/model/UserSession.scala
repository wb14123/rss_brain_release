package me.binwang.rss.model

import java.time.ZonedDateTime

case class UserSession(
  token: String,
  userID: String,
  expireTime: ZonedDateTime,
  isAdmin: Boolean = false,
  subscribeEndTime: ZonedDateTime,
  subscribed: Boolean = false,
)
