package me.binwang.rss.generator

import me.binwang.rss.model.User
import org.scalacheck.Gen

import java.time.{Instant, ZoneId, ZoneOffset, ZonedDateTime}

object Users {

  def get(email: String): User= {
    val genUser= for {
      id <- Gen.uuid
      username <- Gen.asciiPrintableStr
      password <- Gen.asciiPrintableStr.retryUntil(_.nonEmpty)
      salt <- Gen.asciiPrintableStr.retryUntil(_.nonEmpty)
      defaultFolderID <- Gen.uuid
      lastLoginTime <- Gen.calendar
    } yield User(
      id = id.toString,
      username = username,
      password = password,
      salt = salt,
      email = email,
      defaultFolderID = defaultFolderID.toString,
      lastLoginTime = Some(ZonedDateTime.ofInstant(Instant.ofEpochMilli(lastLoginTime.getTimeInMillis / 1000), ZoneId.systemDefault())),
      lastLoginIP = Some("122.122.122.122"),
      isActive = true,
      isAdmin = false,
      createdAt = ZonedDateTime.now().withNano(0),
      subscribeEndAt = ZonedDateTime.now().withNano(0).plusDays(7),
    )
    genUser.sample.get
  }

}
