package me.binwang.rss.parser

import me.binwang.rss.model.ID.ID
import me.binwang.rss.model._

import java.time.ZonedDateTime
import scala.util.Try
import scala.xml.{Elem, Node, NodeSeq}

object AtomParser {

  def parse(url: String, feed: Elem, now: ZonedDateTime): (Source, Seq[Try[FullArticle]]) = {
    val id = SourceID(url)
    val sourceTitle = Some((feed \ "title").text.trim())
    val articles = (feed \ "entry").map(articleNode => parseArticle(url, articleNode, id, sourceTitle, now))

    val failureCount = articles.count(_.isFailure)
    val failedMsg = if (failureCount > 0) {
      Some(s"$failureCount article(s) failed to parse")
    } else {
      None
    }

    val htmlUrl = (feed \ "link")
      .find(link => (link \@ "type").equals("text/html") || !(link \@ "rel").equals("self"))
      .map(_ \@ "href")
    val icon = (feed \ "icon").text.trim()
    val logo = (feed \ "logo").text.trim()
    val iconUrl = if (icon.isEmpty) {
      if (logo.isEmpty) {
        htmlUrl.flatMap(ArticleParserHelper.getIcon)
      } else {
        Some(logo)
      }
    } else {
      Some(icon)
    }

    val source = Source(
      id = id,
      importedAt = now,
      xmlUrl = url,
      title = sourceTitle,
      htmlUrl = htmlUrl,
      iconUrl = iconUrl,
      description = Some((feed \ "subtitle").text.trim()),
      fetchFailedMsg = failedMsg,
      updatedAt = now,
      fetchScheduledAt = now,
    )
    (source, articles)
  }

  private def parseArticle(url: String, articleNode: Node, sourceID: ID, sourceTitle: Option[String], now: ZonedDateTime): Try[FullArticle] = Try {
    val rawText = (articleNode \ "content").text.trim
    val mediaGroups = ArticleParserHelper.parseMediaGroups(articleNode, rawText)
    val text = if (rawText.isEmpty) {
      mediaGroups.map(
        _.groups
          .filter(_.description.nonEmpty)
          .flatMap(_.description.get.split('\n').map(line => s"<p>$line</p>"))
          .mkString
      ).getOrElse("")
    } else {
      rawText
    }

    val comments = Try((articleNode \ "comments").text.trim().toInt).toOption
    val upvotes = Try((articleNode \ "upvotes").text.trim().toInt).toOption
    val downvotes = Try((articleNode \ "downvotes").text.trim().toInt).toOption

    val guidStr = (articleNode \ "id").text.trim
    val link = (articleNode \ "link")
      .find(link => (link \@ "type").equals("text/html"))
      .getOrElse((articleNode \ "link").head) \@ "href"
    val guid = if (guidStr.isEmpty) {
      link
    } else {
      guidStr
    }
    val postedTime = getPostedTime(url, articleNode)
    val article = Article(
      id = ArticleID(sourceID, guid),
      sourceID = sourceID,
      sourceTitle = sourceTitle,
      createdAt = now.withNano(0),
      title = (articleNode \ "title").headOption.map(_.text.trim).getOrElse(""),
      description = ArticleParserHelper.tripHtmlTags(text),
      postedAt = postedTime.getOrElse(now),
      guid = guid,
      link = link,
      mediaGroups = mediaGroups,
      upVotes = upvotes,
      downVotes = downvotes,
      comments = comments,
      postedAtIsMissing = postedTime.isEmpty,
    )
    FullArticle(article=article.getArticleWithScore, content=ArticleParserHelper.prettyHtml(text))
  }


  private def getPostedTime(url: String, articleNode: NodeSeq): Option[ZonedDateTime] = {
    val published = (articleNode \ "published").text.trim
    val updated = (articleNode \ "updated").text.trim
    ArticleParserHelper.parseTime(published).orElse(
        ArticleParserHelper.parseTime(updated)
    )
  }
}
