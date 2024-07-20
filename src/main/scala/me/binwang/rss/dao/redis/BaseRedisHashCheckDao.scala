package me.binwang.rss.dao.redis

import cats.effect.IO
import com.typesafe.config.ConfigFactory
import me.binwang.rss.dao.HashCheckDao
import org.typelevel.log4cats.LoggerFactory

import scala.concurrent.duration.FiniteDuration

trait BaseRedisHashCheckDao[T] extends HashCheckDao[T] {

  val redisClient: RedisCommand
  val ignoreGetError: Boolean
  val expireTime: FiniteDuration
  def getHash(obj: T): String
  def getKey(obj: T): String
  implicit val loggerFactory: LoggerFactory[IO]

  private val logger = LoggerFactory.getLoggerFromClass[IO](this.getClass)

  private val config = ConfigFactory.load()
  protected val keyPrefix: String = config.getString("redis.keyPrefix")

  override def dropTable(): IO[Unit] = {
    redisClient.flushDB()
  }

  override def exists(obj: T): IO[Boolean] = {
    val key = getKey(obj)
    redisClient.get(key).flatMap {
      case Some(value) if value.equals(getHash(obj)) => redisClient.expire(key, expireTime).map(_ => true)
      case _ => IO.pure(false)
    }
      .handleErrorWith { err =>
        if (ignoreGetError) {
          logger.error(err)(
            s"Error to get value for $key, ignoring this error so that the record can be inserted into db")
            .map(_ => false)
        } else {
          IO.raiseError(err)
        }
      }
  }

  override def insertOrUpdate(obj: T): IO[Unit] = {
    val hash = getHash(obj)
    val key = getKey(obj)
    redisClient.setEx(key, hash, expireTime)
  }
}
