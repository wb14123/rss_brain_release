package me.binwang.rss.parser

import cats.effect.kernel.Clock
import cats.effect.{IO, Resource}
import me.binwang.rss.model._

import java.io.InputStream
import java.time.{ZoneId, ZonedDateTime}
import scala.util.Try
import scala.xml.XML

object SourceParser {

  def parse(url: String, content: Resource[IO, InputStream]): IO[(Source, Seq[Try[FullArticle]])] = {
    content.use { c =>
      Clock[IO].realTimeInstant.flatMap { nowInstant =>
        val now = ZonedDateTime.ofInstant(nowInstant, ZoneId.systemDefault())
        val sourceAndArticles = if (url.startsWith("https://hn.algolia.com/api/v1/search")) {
          IO.pure(HackerNewsParser.parse(url, c, now))
        } else if (RedditJsonParser.getSubRedditFromUrl(url).isDefined) {
          RedditJsonParser.parse(url, c)
        } else {
          val xml = XML.load(c)
          val label = xml.label
          if (label.equals("rss") || label.equals("RDF")) {
            IO.pure(RSSParser.parse(url, xml, now))
          } else if (label.eq("feed")) {
            IO.pure(AtomParser.parse(url, xml, now))
          } else {
            IO.raiseError(new RuntimeException(s"Error to parse source xml, unrecognized label $label"))
          }
        }
        sourceAndArticles.map { case (source, articles) =>
          val newSource = SourcePropertyMatcher.updateSourceWithMatchers(source)
          (newSource, articles)
        }
      }
    }
  }


}
