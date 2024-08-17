package me.binwang.rss.time

import cats.effect.{Clock, IO}

import java.time.{ZoneId, ZonedDateTime}

object NowIO {
  def apply(): IO[ZonedDateTime] = {
    for {
      nowInstant <- Clock[IO].realTimeInstant
      now = ZonedDateTime.ofInstant(nowInstant, ZoneId.systemDefault())
    } yield now
  }
}
