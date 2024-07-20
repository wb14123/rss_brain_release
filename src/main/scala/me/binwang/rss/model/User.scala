package me.binwang.rss.model

import java.time.ZonedDateTime
import java.util.UUID

case class User (
  id: String,
  username: String,
  password: String,
  salt: String,
  email: String,
  defaultFolderID: String,
  lastLoginTime: Option[ZonedDateTime],
  lastLoginIP: Option[String],
  isActive: Boolean,
  isAdmin: Boolean,
  createdAt: ZonedDateTime,
  currentFolderID: Option[String] = None,
  currentSourceID: Option[String] = None,
  activeCode: Option[String] = Some(UUID.randomUUID().toString),
  subscribeEndAt: ZonedDateTime,
  subscribed: Boolean = false,
) {

  def toInfo: UserInfo = {
    UserInfo(
      id = id,
      username = username,
      email = email,
      createdAt = createdAt,
      defaultFolderID = defaultFolderID,
      currentFolderID = currentFolderID,
      currentSourceID = currentSourceID,
      isAdmin = isAdmin,
      subscribeEndAt = subscribeEndAt,
      subscribed = subscribed,
    )
  }

}

case class UserInfo (
  id: String,
  username: String,
  email: String,
  createdAt: ZonedDateTime,
  defaultFolderID: String,
  isAdmin: Boolean,
  subscribeEndAt: ZonedDateTime,
  currentFolderID: Option[String] = None,
  currentSourceID: Option[String] = None,
  subscribed: Boolean = false,
)

case class UserUpdater (
  password: Option[String] = None,
  salt: Option[String] = None,
  email: Option[String] = None,
  lastLoginTime: Option[Option[ZonedDateTime]] = None,
  lastLoginIP: Option[Option[String]] = None,
  isActive: Option[Boolean] = None,
  isAdmin: Option[Boolean] = None,
  currentFolderID: Option[Option[String]] = None,
  currentSourceID: Option[Option[String]] = None,
  activeCode: Option[Option[String]] = None,
  subscribeEndAt: Option[ZonedDateTime] = None,
  subscribed: Option[Boolean] = None,
  username: Option[String] = None,
)
