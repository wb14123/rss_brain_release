package me.binwang.rss.util

import cats.effect.IO
import cats.syntax.all._
import org.typelevel.log4cats.Logger

import java.sql.SQLException
import scala.concurrent.duration._
import scala.util.Random

/**
 * Helper to retry operations failed by a transient database error.
 */
object Retry {

  // https://www.postgresql.org/docs/current/errcodes-appendix.html
  private val SERIALIZATION_FAILURE = "40001"

  val DEFAULT_MAX_RETRIES: Int = 3
  val DEFAULT_BASE_DELAY: FiniteDuration = 100.millis

  /**
   * Check if the error is a PostgreSQL serialization failure. It happens when a transaction tries to write a row
   * that is concurrently changed by another transaction, with REPEATABLE READ or SERIALIZABLE isolation level. It's
   * a transient error and the whole transaction can be retried.
   *
   * The error may be wrapped, so we check the whole cause chain.
   */
  def isSerializationFailure(err: Throwable): Boolean = {
    @annotation.tailrec
    def loop(e: Throwable): Boolean = e match {
      case null => false
      case e: SQLException if SERIALIZATION_FAILURE == e.getSQLState => true
      case e => if (e.getCause == null || (e.getCause eq e)) false else loop(e.getCause)
    }
    loop(err)
  }

  /**
   * Retry the IO if it failed with a PostgreSQL serialization failure.
   *
   * @param io The IO to run. It should be safe to run it multiple times, as it will be retried on failure.
   * @param hint Message to show in the log when a retry happens.
   * @param maxRetries How many times to retry at most.
   * @param baseDelay Delay before the first retry. It will be doubled for each following retry, with a random
   *                  jitter to avoid retrying at the same time.
   */
  def onSerializationFailure[A](io: IO[A], hint: String, maxRetries: Int = DEFAULT_MAX_RETRIES,
      baseDelay: FiniteDuration = DEFAULT_BASE_DELAY)(implicit logger: Logger[IO]): IO[A] = {
    def attempt(retried: Int): IO[A] = io.handleErrorWith { err =>
      if (retried >= maxRetries || !isSerializationFailure(err)) {
        IO.raiseError(err)
      } else {
        val baseDelayMillis = Math.max(baseDelay.toMillis, 1)
        val delayMillis = baseDelayMillis * (1L << retried) + Random.nextLong(baseDelayMillis)
        logger.warn(err)(s"Serialization failure when $hint, " +
          s"retry ${retried + 1}/$maxRetries after $delayMillis ms") >>
          IO.sleep(delayMillis.millis) >>
          attempt(retried + 1)
      }
    }
    attempt(0)
  }

}
