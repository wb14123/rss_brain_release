package me.binwang.rss.sourcefinder
import cats.effect.IO
import cats.implicits._
import org.typelevel.log4cats.LoggerFactory

class MultiSourceFinder(val sourceFinders: Seq[SourceFinder])
    (implicit val loggerFactory: LoggerFactory[IO]) extends SourceFinder {

  private val logger = LoggerFactory.getLoggerFromClass[IO](this.getClass)

  override def findSource(url: String): IO[Seq[SourceResult]] = {
    sourceFinders
      .map(sf => sf.findSource(url).handleErrorWith(e =>
        logger.error(e)(s"Error to find source for url $url with source finder ${sf.getClass}").map(_ => Seq())
      )).parSequence.map(_.flatten)
  }
}
