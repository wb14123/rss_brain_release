package me.binwang.rss.model

import java.time.ZonedDateTime

/**
 * User session.
 * @param token The token used for most of the API requests that needs a `token` parameter.
 * @param expireTime When the token will be expired.
 * @param isAdmin Whether the logged-in user is an admin. May need to login again to refresh this info in session.
 * @param subscribeEndTime When the user's subscription will end. May need to login again to refresh this info in session.
 * @param subscribed If the user is subscribed to the product. May need to login again to refresh this info in session.
 */
case class UserSession(
  token: String,
  userID: String,
  expireTime: ZonedDateTime,
  isAdmin: Boolean = false,
  subscribeEndTime: ZonedDateTime,
  subscribed: Boolean = false,
)
