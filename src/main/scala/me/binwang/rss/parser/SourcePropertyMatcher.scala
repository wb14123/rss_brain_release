package me.binwang.rss.parser

import me.binwang.rss.model.ArticleOrder.ArticleOrder
import me.binwang.rss.model.{ArticleOrder, Source}

import scala.util.matching.Regex

case class SourcePropertyMatcher(
    name: String,
    matchXmlUrl: Option[Regex] = None,
    matchHtmlUrl: Option[Regex] = None,

    setShowTitle: Option[Boolean] = None,
    setShowMedia: Option[Boolean] = None,
    setShowFullArticle: Option[Boolean] = None,
    setArticleOrder: Option[ArticleOrder] = None,
)

object SourcePropertyMatcher {

  val defaultMatchers: Seq[SourcePropertyMatcher] = Seq(
    SourcePropertyMatcher(
      name = "TwitterMatcher",
      matchHtmlUrl = Some("https?://twitter.com/.+".r),
      setShowTitle = Some(false),
      setShowMedia = Some(true),
      setShowFullArticle = Some(true),
    ),
    SourcePropertyMatcher(
      name = "WeiboMatcher",
      matchHtmlUrl = Some("https?://(\\w+.|)weibo.(com|cn)/.+".r),
      setShowTitle = Some(false),
      setShowMedia = Some(true),
      setShowFullArticle = Some(true),
    ),
    SourcePropertyMatcher(
      name = "HotRedditMatcher",
      matchXmlUrl = Some("https?://www.reddit.com/r/(\\S+)/hot.json(.*)".r),
      setShowMedia = Some(true),
      setArticleOrder = Some(ArticleOrder.SCORE),
    ),
    SourcePropertyMatcher(
      name = "RedditMatcher",
      matchHtmlUrl = Some("https?://www.reddit.com/(.*)".r),
      setShowMedia = Some(true),
    ),
    SourcePropertyMatcher(
      name = "HackerNewsMatcher",
      matchHtmlUrl = Some("https?://news.ycombinator.com(.*)".r),
      setArticleOrder = Some(ArticleOrder.SCORE),
    )
  )

  def findMatch(source: Source, matchers: Seq[SourcePropertyMatcher] = defaultMatchers
      ): Seq[SourcePropertyMatcher] = {
    matchers.filter(m => m.matchXmlUrl.exists(_.matches(source.xmlUrl)) ||
      m.matchHtmlUrl.exists(_.matches(source.htmlUrl.getOrElse(""))))
  }

  def updateSourceWithMatchers(source: Source, matchers: Seq[SourcePropertyMatcher] = defaultMatchers): Source = {
    findMatch(source, matchers).foldLeft(source){ (oldSource, matcher) =>
      oldSource.copy(
        showMedia = matcher.setShowMedia.getOrElse(oldSource.showMedia),
        showTitle = matcher.setShowTitle.getOrElse(oldSource.showTitle),
        showFullArticle = matcher.setShowFullArticle.getOrElse(oldSource.showFullArticle),
        articleOrder = matcher.setArticleOrder.getOrElse(oldSource.articleOrder),
      )
    }
  }

}
