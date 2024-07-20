package me.binwang.rss.dao.redis

import cats.effect.unsafe.IORuntime
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.DurationInt

class RedisCommandSpec extends AnyFunSpec with BeforeAndAfterEach with Matchers {

  implicit val ioRuntime: IORuntime = IORuntime.global

  override def beforeEach(): Unit = {
    val redisCommand = RedisCommand().allocated.unsafeRunSync()._1
    redisCommand.flushDB().unsafeRunSync()
  }

  describe("redis command wrapper") {

    it("should get value from key") {
      val redisCommand = RedisCommand().allocated.unsafeRunSync()._1
      redisCommand.get("this_key").unsafeRunSync() shouldBe None
      redisCommand.setEx("this_key", "this_value", 1.days).unsafeRunSync()
      redisCommand.get("this_key").unsafeRunSync() shouldBe Some("this_value")
    }

  }

}
