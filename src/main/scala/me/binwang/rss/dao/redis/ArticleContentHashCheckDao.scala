package me.binwang.rss.dao.redis

import cats.effect.IO
import io.circe.generic.auto._
import io.circe.syntax._
import me.binwang.rss.model.{ArticleContent, IDMurmurHash}
import org.typelevel.log4cats.LoggerFactory

import scala.concurrent.duration.{DurationInt, FiniteDuration}

class ArticleContentHashCheckDao(
    override val redisClient: RedisCommand,
    override val ignoreGetError: Boolean = true,
    override val expireTime: FiniteDuration = 2.hours)
    (implicit val loggerFactory: LoggerFactory[IO]) extends BaseRedisHashCheckDao[ArticleContent] {

  override def getKey(article: ArticleContent) = s"${keyPrefix}_content_${article.id}"
  override def getHash(article: ArticleContent): String = IDMurmurHash.hash(article.asJson.noSpaces)
}
