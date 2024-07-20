package me.binwang.rss.sourcefinder
import cats.effect.IO
import me.binwang.rss.fetch.crawler.Crawler
import org.jsoup.Jsoup

import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters._

// find feed from html source
class HtmlSourceFinder(implicit val crawler: Crawler) extends SourceFinder {

  private val rssLinkPatter = "^https?://.*(feed|rss|atom)(\\.(xml|rss|atom))?$".r

  private val rssTypes = Seq(
    "application/rss+xml",
    "application/atom+xml",
    "application/rdf+xml",
    "application/rss",
    "application/atom",
    "application/rdf",
    "text/rss+xml",
    "text/atom+xml",
    "text/rdf+xml",
    "text/rss",
    "text/atom",
    "text/rdf",
  )

  override def findSource(url: String): IO[Seq[SourceResult]] = {
    crawler.fetch(url).use { inputStream =>
      val dom = Jsoup.parse(inputStream, StandardCharsets.UTF_8.name(), url)
      val result = dom.select("link")
        .asScala
        .toSeq
        .filter{ link =>
          val typ = link.attr("type")
          (typ.isEmpty && rssLinkPatter.findFirstMatchIn(link.attr("href")).isDefined) ||
            rssTypes.contains(typ)
        }
        .map(link => SourceResult(url = link.attr("href")))
      IO.pure(result)
    }
  }
}
