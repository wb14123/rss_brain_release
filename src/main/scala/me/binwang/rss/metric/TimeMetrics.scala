package me.binwang.rss.metric

import cats.effect.IO

import scala.concurrent.duration.FiniteDuration
import me.binwang.archmage.core.CatsMacroImpl.MethodMeta

trait TimeMetrics {
  implicit val handleTime: (MethodMeta, FiniteDuration) => IO[Unit] = (method: MethodMeta, time: FiniteDuration) => {
    MetricReporter.methodTime(method.name, time)
  }
}
