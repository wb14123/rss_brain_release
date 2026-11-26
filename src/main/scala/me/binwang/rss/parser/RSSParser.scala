package me.binwang.rss.parser

import me.binwang.rss.model.ID.ID
import me.binwang.rss.model._

import java.time.ZonedDateTime
import scala.util.Try
import scala.xml.{Elem, Node}
import XmlNamespace._

object RSSParser {

  def parse(url: String, xml: Elem, now: ZonedDateTime): (Source, Seq[Try[FullArticle]]) = {
    val channel = xml \ "channel"
    val id = SourceID(url)
    val sourceTitle = Some((channel \ "title").text.trim())
    val articles = (xml \\ "item").map(articleNode => parseArticle(articleNode, id, sourceTitle, now))

    val failureCount = articles.count(_.isFailure)
    val failedMsg = if (failureCount > 0) {
      Some(s"$failureCount article(s) failed to parse")
    } else {
      None
    }

    val htmlUrl = (channel \ "link").text
    val iconUrl = (channel \\ "icon")
      .find(_.prefix == "webfeeds")
      .map(_.text)
      .find(x => x != null && x.nonEmpty)
      .orElse(Some((channel \ "image" \ "url").text))
      .find(x => x != null && x.nonEmpty)
      .orElse(ArticleParserHelper.getIcon(htmlUrl))

    val source = Source(
      id = id,
      importedAt = now,
      xmlUrl = url,
      title = sourceTitle,
      htmlUrl = Some(htmlUrl),
      iconUrl = iconUrl,
      description = Some((channel \ "description").text.trim()),
      fetchFailedMsg = failedMsg,
      updatedAt = now,
      fetchScheduledAt = now,
    )
    (source, articles)
  }

  private def parseArticle(articleNode: Node, sourceID: ID, sourceTitle: Option[String], now: ZonedDateTime): Try[FullArticle] = Try {
    val encoded = (articleNode \\ "encoded").text.trim
    val description = (articleNode \ "description").headOption.map(_.text.trim).getOrElse("")
    val text = if (encoded.isBlank) description else encoded

    val mediaGroups = ArticleParserHelper.parseMediaGroups(articleNode, text).orElse(parsePodcastMedia(articleNode))
    val mediaUrl = mediaGroups.flatMap(_.groups
      .find(g => g.content.url.nonEmpty && !g.content.fromArticle.getOrElse(false))
      .map(_.content.url)
    )
    val link = Option((articleNode \ "link").text.trim).filter(_.nonEmpty).orElse(mediaUrl).getOrElse("")
    val guidStr = (articleNode \ "guid").text.trim
    val guid = if (guidStr.isEmpty) {
      link
    } else {
      guidStr
    }
    // support "dc:date"
    val postedTime = ArticleParserHelper.parseTime((articleNode \ "pubDate").text.trim)
      .orElse(ArticleParserHelper.parseTime((articleNode \\ "date").text.trim))
    val article = Article(
      id = ArticleID(sourceID, guid),
      sourceID = sourceID,
      sourceTitle = sourceTitle,
      createdAt = now.withNano(0),
      title = (articleNode \ "title").headOption.map(_.text.trim).getOrElse(""),
      description = ArticleParserHelper.tripHtmlTags(description),
      author = getAuthor(articleNode),
      postedAt = postedTime.getOrElse(now),
      guid = guid,
      link = link,
      mediaGroups = mediaGroups,
      postedAtIsMissing = postedTime.isEmpty,
    )
    FullArticle(article=article.getArticleWithScore, content=ArticleParserHelper.prettyHtml(text))
  }

  // Get the author of an item. Follow this order: dc:creator, itunes:author, author.
  private def getAuthor(articleNode: Node): Option[String] = {
    val creator = (articleNode \\ "creator").headOption.map(_.text.trim).filter(_.nonEmpty)
    val authorNodes = articleNode \ "author"
    val itunesAuthor = authorNodes.filterNamespace(XmlNamespace.itunes)
      .headOption.map(_.text.trim).filter(_.nonEmpty)
    val plainAuthor = authorNodes
      .filterNot(n => n.namespace != null && n.namespace.equals(XmlNamespace.itunes))
      .headOption
      .flatMap(parseRssAuthorElement)
    creator.orElse(itunesAuthor).orElse(plainAuthor)
  }

  /*
   * RSS 2.0 puts the email address of the author in the author element. The name of the author can follow the
   * email address in parentheses. Some feeds put an atom author element in the item instead, which has a name
   * child element.
   */
  private def parseRssAuthorElement(node: Node): Option[String] = {
    val value = (node \ "name").headOption.map(_.text).getOrElse(node.text).trim
    val emailWithName = """^[^@\s]+@[^@\s]+\s*\((.*)\)$""".r
    val name = value match {
      case emailWithName(realName) => realName.trim
      case _ => value
    }
    Some(name).filter(_.nonEmpty)
  }

  // parse enclosure and itunes related tags
  private def parsePodcastMedia(articleNode: Node): Option[MediaGroups] = {
    val thumbnail = (articleNode \\ "image")
      .filterNamespace(XmlNamespace.itunes)
      .map(_ \@ "href")
      .map(MediaThumbnail(_))
    (articleNode \ "enclosure").headOption.map { enclosureElem =>
      val enclosureUrl = (enclosureElem \@ "url").trim
      val enclosureType = (enclosureElem \@ "type").trim
      val fileSizeStr = (enclosureElem \@ "length").trim
      val fileSize = if (fileSizeStr.isEmpty) None else Some(fileSizeStr.toLong)
      val mediaGroup = MediaGroup(
        content = MediaContent(
          url = enclosureUrl,
          fileSize = fileSize,
          typ = Option(enclosureType).filter(_.nonEmpty),
          medium = ArticleParserHelper.getMedium(enclosureType),
        ),
        title = (articleNode \\ "title").filterNamespace(XmlNamespace.itunes).headOption.map(_.text.trim),
        thumbnails = thumbnail,
      )
      MediaGroups(Seq(mediaGroup))
    }

  }
}
