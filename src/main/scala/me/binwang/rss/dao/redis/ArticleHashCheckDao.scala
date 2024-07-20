package me.binwang.rss.dao.redis

import cats.effect.IO
import io.circe.generic.auto._
import io.circe.syntax._
import me.binwang.rss.model.{Article, IDMurmurHash}
import org.typelevel.log4cats.LoggerFactory

import java.time.{Instant, ZoneId, ZonedDateTime}
import scala.concurrent.duration.{DurationInt, FiniteDuration}

class ArticleHashCheckDao(
    override val redisClient: RedisCommand,
    override val ignoreGetError: Boolean = true,
    override val expireTime: FiniteDuration = 2.hours)
    (implicit val loggerFactory: LoggerFactory[IO]) extends BaseRedisHashCheckDao[Article] {

  override def getKey(article: Article) = s"${keyPrefix}_article_${article.id}"
  override def getHash(article: Article): String = {
    val zeroTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(0), ZoneId.systemDefault())
    // ignore the difference of createdAt field
    val cmpArticle = if (article.postedAtIsMissing) {
      article.copy(createdAt = zeroTime, postedAt = zeroTime, score = 0)
    } else {
      article.copy(createdAt = zeroTime)
    }
    IDMurmurHash.hash(cmpArticle.asJson.noSpaces)
  }

}
