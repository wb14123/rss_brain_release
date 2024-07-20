package me.binwang.rss.sourcefinder

import cats.effect.IO
import io.circe.generic.JsonCodec
import io.circe.parser
import me.binwang.rss.sourcefinder.RegexSourceFinder.matchRule

import java.nio.charset.StandardCharsets
import scala.util.matching.Regex

/**
 *
 * @param source The regex to match url. Extract params from named groups.
 *               e.g. "https://www.youtube.com/channel/(?<channel>\\w+)".r,
 *
 * @param target The source url. use $name to use the matched group in source.
 *               e.g. https://www.youtube.com/feeds/videos.xml?channel_id=$channel
 *
 * @param recommend Whether this source is recommended over user input.
 *
 * @param recommendReason THe reason for recommendation.
 */
case class RegexMatchRule(
   source: Regex,
   target: String,
   names: Seq[String],
   recommend: Boolean = false,
   recommendReason: Option[String] = None,
)


object RegexSourceFinder  {

  @JsonCodec case class RegexMatchRuleJson(
      source: String,
      target: String,
      recommend: Option[Boolean],
      recommendReason: Option[String]
  )

  @JsonCodec case class RegexMatchRulesJson(
      rules: Seq[RegexMatchRuleJson]
  )

  private def loadRulesFromFile(filePath: String, classLoader: Option[ClassLoader]): IO[Seq[RegexMatchRule]] = {
    val loader = classLoader.getOrElse(getClass.getClassLoader)
    IO.blocking(loader.getResourceAsStream(filePath)).flatMap { is =>
      val content = new String(is.readAllBytes(), StandardCharsets.UTF_8)
      is.close()
      parser.parse(content).flatMap(_.as[RegexMatchRulesJson]) match {
        case Left(err) => IO.raiseError(err)
        case Right(rules) =>
          IO.pure(rules.rules.map{ r =>
            val names = parseRegexGroupNames(r.source)
            val regex = new Regex(r.source, names: _*)
            RegexMatchRule(regex, r.target, names, r.recommend.getOrElse(false), r.recommendReason)})
      }
    }
  }

  def parseRegexGroupNames(source: String): Seq[String] = {
    val namePatter = "/?<(\\w+)>".r
    namePatter.findAllMatchIn(source).map(_.group(1)).toSeq
  }

  def apply(filePath: String, classLoader: Option[ClassLoader] = None): IO[RegexSourceFinder] = {
    loadRulesFromFile(filePath, classLoader).map(new RegexSourceFinder(_))
  }

  def matchRule(url: String, rule: RegexMatchRule): Option[SourceResult] = {
    rule.source.findFirstMatchIn(url).map { m =>
      val sourceUrl = rule.names.fold(rule.target) { case (lastUrl, name) =>
        lastUrl.replaceAll("\\$" + name, m.group(name))
      }
      SourceResult(
        url = sourceUrl,
        recommend = rule.recommend,
        recommendReason = rule.recommendReason,
      )
    }
  }

}


class RegexSourceFinder(val rules: Seq[RegexMatchRule]) extends SourceFinder {
  override def findSource(url: String): IO[Seq[SourceResult]] = {
    IO.pure(rules.map(matchRule(url, _)).filter(_.isDefined).map(_.get))
  }
}
