package me.binwang.rss.parser

import me.binwang.rss.model.{MediaContent, MediaGroup, MediaGroups, MediaMedium, MediaThumbnail}
import org.jsoup.Jsoup
import org.jsoup.nodes.Document.OutputSettings
import org.jsoup.safety.{Cleaner, Safelist}
import org.slf4j.LoggerFactory

import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import scala.jdk.CollectionConverters._
import scala.util.Try
import scala.xml.Node

object ArticleParserHelper {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def tripHtmlTags(html: String, cutChars: Option[Int] = Some(512)): String = {
    val cleanedHtml = prettyHtml(html, Safelist.basic(), Seq("img", "figure", "figurecaption"))
    val subHtml = cutChars match {
      case None => cleanedHtml
      case Some(n) => cleanedHtml.substring(0, Math.min(n, cleanedHtml.length))
    }
    Jsoup.parse(subHtml).body().html()
  }

  def prettyHtml(html: String, safeList: Safelist = Safelist.relaxed().addTags("figure", "figcaption"),
      removeTags: Seq[String] = Seq()) : String = {
    try {
      val settings = new OutputSettings()
        .prettyPrint(true)
        .charset("UTF-8")
        .outline(true)
        .indentAmount(2)
      val dom = Jsoup.parse(html).outputSettings(settings)
      removeTags.foreach(dom.select(_).remove())
      val cleaner = new Cleaner(safeList)
      val cleaned = cleaner.clean(dom).body()
      var cleanAgain = true
      while (cleanAgain) {

        // remove div with nothing in it
        val cleanedEmptyDiv = cleaned.select("div")
          .asScala
          .filter(_.html().strip().isEmpty)
          .map(_.remove())
          .size

        // remove div with nothing in it
        val cleanedEmptySpan = cleaned.select("span")
          .asScala
          .filter(_.html().strip().isEmpty)
          .map(_.remove())
          .size

        // unwrap div with only one div child in it
        val unwrappedDiv = cleaned.select("div")
          .asScala
          .filter(_.children().size() == 1)
          .filter(_.attributes().size() <= 1)
          .filter(_.children().first().is("div"))
          .map(_.unwrap())
          .size

        // replace span with div if there is any node shouldn't in span
        val tagsNotAllowedInSpan = Seq("blockquote", "code", "col",
          "colgroup", "dd", "div", "dl", "dt", "h1", "h2", "h3", "h4", "h5", "h6",
          "img", "p", "pre", "table", "tbody", "td", "tfoot", "th", "thead", "tr", "ul")
        val replacedSpan = cleaned.select("span")
          .asScala
          .filter{span => tagsNotAllowedInSpan.exists(span.select(_).size() > 0)}
          .map(_.tagName("div"))
          .size

        cleanAgain = (cleanedEmptyDiv + cleanedEmptySpan + unwrappedDiv + replacedSpan) > 0
      }
      cleaned.html()
    } catch {
      case e: Throwable =>
        logger.info(s"Exception to parse $html", e)
        html
    }
  }

  def getIcon(htmlUrl: String): Option[String] = {
    Try {new URL(htmlUrl)}.toOption.map(url => s"${url.getProtocol}://${url.getHost}/favicon.ico")
  }

  def parseTime(dateStr: String): Option[ZonedDateTime] = {
    val formatters = Seq(
      DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz"),
      DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z"),
      DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss zzz"),
      DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z"),
      DateTimeFormatter.ISO_ZONED_DATE_TIME,
      DateTimeFormatter.ISO_OFFSET_DATE_TIME,
    )
    formatters.map { formatter => Try {ZonedDateTime.parse(dateStr, formatter)}.toOption}
      .filter(_.isDefined)
      .map(_.get)
      .headOption
  }

  private def getThumbnailFromNode(thumbnailNode: Node): MediaThumbnail = {
    MediaThumbnail(
      url = thumbnailNode \@ "url",
      width = Option(thumbnailNode \@ "width").filter(_.nonEmpty).flatMap(x => Try(Integer.parseInt(x)).toOption),
      height = Option(thumbnailNode \@ "height").filter(_.nonEmpty).flatMap(x => Try(Integer.parseInt(x)).toOption),
    )
  }

  def parseMediaGroups(articleNode: Node, html: String): Option[MediaGroups] = {

    val groups = (articleNode \\ "group").map { n => (n \\ "content").headOption.map {content =>
      val typ = Option(content \@ "type").filter(_.nonEmpty)

      // For some feed like Peertube, information like thumbnails, title and description can be out of media group
      val thumbnails = (n \\ "thumbnail").map(getThumbnailFromNode) ++ (articleNode \ "thumbnail").map(getThumbnailFromNode)

      MediaGroup(
        content = MediaContent(
          url = content \@ "url",
          typ = typ,
          medium = Option(content \@ "medium").filter(_.nonEmpty).orElse(typ.flatMap(getMedium)),
          width = Option(content \@ "width").filter(_.nonEmpty).flatMap(x => Try(Integer.parseInt(x)).toOption),
          height = Option(content \@ "height").filter(_.nonEmpty).flatMap(x => Try(Integer.parseInt(x)).toOption),
        ),
        title = (n \\ "title").headOption.orElse((articleNode \ "title").headOption).map(_.text.trim),
        description = (n \\ "description").headOption.orElse((articleNode \ "description").headOption).map(_.text.trim),
        thumbnails = thumbnails
      )
    }}.filter(_.isDefined).map(_.get)

    if (groups.isEmpty) {
      getImgFromHtml(html) // only parse from article content when there is no media tags
    } else {
      Some(MediaGroups(groups))
    }

  }

  private def getImgFromHtml(html: String): Option[MediaGroups] = {
    val dom = Jsoup.parse(html)
    val groups = dom.select("img").asScala.toSeq.map { img =>
      MediaGroup(
        content = MediaContent(
          img.attr("src"),
          medium = Some(MediaMedium.IMAGE),
          fromArticle = Some(true),
          width = Try(img.attr("width").toInt).toOption,
          height = Try(img.attr("height").toInt).toOption,
        ),
        title = Option(img.attr("title")).filter(_.trim.nonEmpty),
      )
    }.filter(group => // only filter images that are large enough
      (group.content.width.isEmpty || group.content.width.get > 64) &&
      (group.content.height.isEmpty || group.content.height.get > 64))
    if (groups.isEmpty) {
      None
    } else {
      Some(MediaGroups(groups))
    }
  }

  def getMedium(typ: String): Option[String] = {
    if (typ.contains("x-shockwave-flash") || typ.contains("video")) {
      Some(MediaMedium.VIDEO)
    } else if (typ.contains("image")) {
      Some(MediaMedium.IMAGE)
    } else if (typ.contains("audio")) {
      Some(MediaMedium.AUDIO)
    } else {
      None
    }
  }

}
