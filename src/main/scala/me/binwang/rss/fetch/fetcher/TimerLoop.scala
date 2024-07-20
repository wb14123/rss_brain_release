package me.binwang.rss.fetch.fetcher
import cats.effect.IO

import scala.concurrent.duration.DurationLong

object TimerLoop {
  def apply(func: () => IO[Boolean], intervalMillis: Long): IO[Unit] = {
    func().flatMap {
      case true => TimerLoop(func, intervalMillis)
      case false =>
        IO.sleep(intervalMillis.millis)
          .flatMap(_ => TimerLoop(func, intervalMillis))
    }
  }

}
