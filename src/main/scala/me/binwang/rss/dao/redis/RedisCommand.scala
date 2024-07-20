package me.binwang.rss.dao.redis

import cats.effect.{IO, Resource}
import com.typesafe.config.ConfigFactory
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.{RedisClient, RedisFuture}
import me.binwang.rss.dao.redis.RedisCommand.redisFutureToIO
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

import java.util.function.BiFunction
import scala.concurrent.duration.FiniteDuration
import scala.language.implicitConversions

object RedisCommand {

  private implicit val loggerFactory: LoggerFactory[IO] = Slf4jFactory.create[IO]
  private val logger = LoggerFactory.getLoggerFromClass[IO](this.getClass)

  implicit def redisFutureToIO[T](redisFuture: RedisFuture[T]): IO[T] = {
    IO.async_ { cb =>
      redisFuture.handle(new BiFunction[T, Throwable, T]() {
        override def apply(t: T, u: Throwable): T = {
          if (u != null) {
            cb(Left(u))
            t
          } else {
            cb(Right(t))
            t
          }
        }
      })
    }
  }

  def apply(): Resource[IO, RedisCommand] = {
    val config = ConfigFactory.load()
    val redisUrl = config.getString("redis.url")
    val command = for {
      _ <- logger.info(s"Initializing redis connection to $redisUrl ...")
      client = RedisClient.create(redisUrl)
      connection = client.connect().async()
      _ <- logger.info(s"Initialized redis connection to $redisUrl")
      cmd = new RedisCommand(connection, client)
    } yield cmd
    Resource.make(command)(cmd => cmd.close)
  }
}

class RedisCommand(connection: RedisAsyncCommands[String, String], client: RedisClient) {

  def close: IO[Unit] = {
    IO(client.close())
  }

  def get(key: String): IO[Option[String]] = {
    val value: IO[String] = connection.get(key)
    value.map(Option(_))
  }

  def flushDB(): IO[Unit] = {
    val result: IO[String] = connection.flushdb()
    result.map(_ => ())
  }

  def setEx(key: String, value: String, time: FiniteDuration): IO[Unit] = {
    val result: IO[String] = connection.setex(key, time.toSeconds, value)
    result.map(_ => ())
  }

  def expire(key: String, time: FiniteDuration): IO[Boolean] = {
    connection.expire(key, time.toSeconds).map(b => Boolean.box(b))
  }

}
