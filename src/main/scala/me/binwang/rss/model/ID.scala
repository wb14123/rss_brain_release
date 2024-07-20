package me.binwang.rss.model

import org.apache.commons.codec.digest.DigestUtils

import java.security.MessageDigest

object ID {
  type ID = String
  val maxLength: Int = 32

  val m: MessageDigest = MessageDigest.getInstance("MD5")

  def hash(str: String): ID = {
    DigestUtils.md5Hex(str).padTo(maxLength, '0')
  }
}
