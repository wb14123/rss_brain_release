package me.binwang.rss.util

import com.google.common.cache.{CacheBuilder, CacheLoader, LoadingCache}
import com.typesafe.config.ConfigFactory
import io.github.bucket4j.{Bandwidth, Bucket}
import me.binwang.rss.model.RateLimitExceedError

import java.time.Duration
import java.util.concurrent.TimeUnit

object Throttler {
  def apply(): Throttler = {
    val config = ConfigFactory.load()
    new Throttler(
      config.getLong("throttling.per-user.requests-per-minute"),
      config.getInt("throttling.per-user.max-size"),
      config.getLong("throttling.per-ip.requests-per-minute"),
      config.getInt("throttling.per-ip.max-size"),
      config.getLong("throttling.login-per-ip.requests-per-minute"),
    )
  }
}

class Throttler(
  private val requestPerMinutePerUser: Long,
  private val maxUsers: Int,
  private val requestPerMinutePerIP: Long,
  private val maxIPs: Int,
  private val loginsPerMinutePerIP: Long,
) {

  private val userBuckets = getBuckets(requestPerMinutePerUser, maxUsers)
  private val ipBuckets = getBuckets(requestPerMinutePerIP, maxIPs)
  private val loginBuckets = getBuckets(loginsPerMinutePerIP, maxIPs)

  def throttleUser(userID: String): Unit = {
    if (!userBuckets.get(userID).tryConsume(1)) {
      throw RateLimitExceedError(userID, "user", requestPerMinutePerUser)
    }
  }

  def throttleIP(ip: String): Unit = {
    if (!ipBuckets.get(ip).tryConsume(1)) {
      throw RateLimitExceedError(ip, "ip", requestPerMinutePerIP)
    }
  }

  def throttleLogin(ip: String): Unit = {
    if (!loginBuckets.get(ip).tryConsume(1)) {
      throw RateLimitExceedError(ip, "auth", loginsPerMinutePerIP)
    }
  }

  private def getBuckets(requestPerMinute: Long, maxSize: Int): LoadingCache[String, Bucket] = {
    val bucketBuilder = Bucket.builder()
      .addLimit(Bandwidth.simple(requestPerMinute, Duration.ofMinutes(1)))
    CacheBuilder.newBuilder()
      .maximumSize(maxSize)
      .expireAfterAccess(2, TimeUnit.MINUTES)
      .build[String, Bucket](
        new CacheLoader[String, Bucket] {
          override def load(key: String): Bucket = {
            bucketBuilder.build()
          }
        }
      )
  }


}
