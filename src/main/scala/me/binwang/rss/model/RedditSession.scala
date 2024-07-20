package me.binwang.rss.model

import java.time.ZonedDateTime
import java.util.UUID

case class RedditSession (
    userID: String,
    redditUserID: String = UUID.randomUUID().toString, // use a random ID when the user is not authorized
    redditUserName: Option[String] = None,
    state: String = UUID.randomUUID().toString,
    createdAt: ZonedDateTime,
    accessAcceptedAt: Option[ZonedDateTime] = None,
    token: Option[String] = None,
    refreshToken: Option[String] = None,
    scope: Option[String] = None,
    expiresInSeconds: Option[Int] = None,
)
