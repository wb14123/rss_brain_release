package me.binwang.rss.model

import scala.util.hashing.MurmurHash3

object IDMurmurHash {
  type IDMurmurHash = String
  val maxLength: Int = Math.max(Int.MaxValue.toHexString.length, Int.MinValue.toHexString.length)

  def hash(str: String): IDMurmurHash = MurmurHash3.stringHash(str).toHexString.padTo(maxLength, '0')
}
