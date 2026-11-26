package me.binwang.rss.util

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.log4cats.{Logger, LoggerFactory}
import org.typelevel.log4cats.slf4j.Slf4jFactory

import java.sql.SQLException
import scala.concurrent.duration._

class RetrySpec extends AnyFunSpec with Matchers {

  implicit val ioRuntime: IORuntime = IORuntime.global
  implicit val loggerFactory: LoggerFactory[IO] = Slf4jFactory.create[IO]
  private implicit val logger: Logger[IO] = LoggerFactory.getLoggerFromClass[IO](this.getClass)

  private def serializationFailure() =
    new SQLException("could not serialize access due to concurrent update", "40001")

  describe("Retry") {

    describe("isSerializationFailure") {

      it("should detect serialization failure") {
        Retry.isSerializationFailure(serializationFailure()) shouldBe true
      }

      it("should detect wrapped serialization failure") {
        Retry.isSerializationFailure(new RuntimeException("wrapped", serializationFailure())) shouldBe true
      }

      it("should not treat other sql errors as serialization failure") {
        Retry.isSerializationFailure(new SQLException("duplicate key", "23505")) shouldBe false
      }

      it("should not treat other errors as serialization failure") {
        Retry.isSerializationFailure(new RuntimeException("mock error")) shouldBe false
      }
    }

    describe("onSerializationFailure") {

      it("should retry until success") {
        var count = 0
        val io = IO {
          count += 1
          if (count < 3) {
            throw serializationFailure()
          } else {
            count
          }
        }
        Retry.onSerializationFailure(io, "mock operation", 3, 1.millisecond).unsafeRunSync() shouldBe 3
        count shouldBe 3
      }

      it("should raise the error after retries are exhausted") {
        var count = 0
        val io = IO {
          count += 1
          throw serializationFailure()
        }
        intercept[SQLException] {
          Retry.onSerializationFailure(io, "mock operation", 2, 1.millisecond).unsafeRunSync()
        }
        count shouldBe 3
      }

      it("should not retry other errors") {
        var count = 0
        val io = IO {
          count += 1
          throw new RuntimeException("mock error")
        }
        intercept[RuntimeException] {
          Retry.onSerializationFailure(io, "mock operation", 3, 1.millisecond).unsafeRunSync()
        }
        count shouldBe 1
      }
    }
  }

}
