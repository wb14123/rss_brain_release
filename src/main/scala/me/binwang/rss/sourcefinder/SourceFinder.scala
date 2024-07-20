package me.binwang.rss.sourcefinder

import cats.effect.IO

trait SourceFinder {
  def findSource(url: String): IO[Seq[SourceResult]]
}
