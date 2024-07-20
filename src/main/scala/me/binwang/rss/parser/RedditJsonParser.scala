package me.binwang.rss.parser

import cats.effect.{Clock, IO}
import io.circe.parser
import me.binwang.rss.model._
import me.binwang.rss.reddit.RedditModels.{RedditPost, RedditPostList}

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.time.{Instant, ZoneId, ZonedDateTime}
import scala.concurrent.duration.DurationInt
import scala.util.Try
import scala.util.matching.Regex

// Parser for Reddit API. For example: https://www.reddit.com/r/javascript/hot.json?count=100
object RedditJsonParser {

  private val subRedditPattern: Regex = """www.reddit.com/r/(\S+)/(\S+)\.json""".r

  def getSubRedditFromUrl(url: String): Option[String] = {
    subRedditPattern.findFirstMatchIn(url).map(_.group(1))
  }

  private def getSubRedditOrder(url: String): Option[String] = {
    subRedditPattern.findFirstMatchIn(url).map(_.group(2))
  }


  private[parser] def decodeRedditHtml(html: String): String = {
    org.jsoup.parser.Parser.unescapeEntities(html, true)
  }

  private def getSource(url: String): IO[Source] = {
    val subRedditStr = getSubRedditFromUrl(url).get
    val order = getSubRedditOrder(url).get
    for {
      instantNow <- Clock[IO].realTimeInstant
      now = ZonedDateTime.ofInstant(instantNow, ZoneId.systemDefault())
    } yield Source(
      id = SourceID(url),
      xmlUrl = url,
      title = Some(s"/r/$subRedditStr"),
      htmlUrl = Some(s"https://www.reddit.com/r/$subRedditStr"),
      description = Some(s"Reddit /r/$subRedditStr sort by $order"),
      iconUrl = Some("https://www.reddit.com/favicon.ico"),
      fetchDelayMillis = 30.minutes.toMillis, // fetch every 30 minutes
      importedAt = now,
      updatedAt = now,
      fetchScheduledAt = now,
    )
  }

  def parse(url: String, content: InputStream): IO[(Source, Seq[Try[FullArticle]])] = {
    val contentStr = new String(content.readAllBytes(), StandardCharsets.UTF_8)
    getSource(url).map { source =>
      val eitherResult = parser.parse(contentStr)
        .flatMap(_.as[RedditPostList])
        .map(_.data
          .children
          .map(_.data)
          .map(postToArticle(source, _))
        )
      eitherResult match {
        case Left(e) => throw e
        case Right(result) => (source, result)
      }
    }
  }

  private def parseMediaUrl(url: String) = url.replaceAll("amp;", "")

  private def postToArticle(source: Source, post: RedditPost): Try[FullArticle] = Try {
    val guid = post.id
    val htmlUrl = s"https://www.reddit.com${post.permalink}"
    val content = if (post.selftextHtml.isDefined) {
      val html = post.selftextHtml.get
      decodeRedditHtml(html)
    } else {
      s"<a href='${post.url.get}' target='_blank' rel='noreferrer noopener'>${post.url.get}</a>"
    }

    val groups: Option[Seq[MediaGroup]] = if(post.postHint.exists(_.contains("image"))) {
      post.preview.flatMap(_.images.map(_.map { img =>
        MediaGroup(
          content =MediaContent(url = parseMediaUrl(img.source.url), width = Some(img.source.width),
            height = Some(img.source.height), medium = Some(MediaMedium.IMAGE)),
          thumbnails = img.resolutions.map(res =>
            MediaThumbnail(url = parseMediaUrl(res.url), width = Some(res.width), height = Some(res.height)))
        )
      }))
    } else if (post.postHint.exists(_.equals("hosted:video"))) {
      val content = post.media.flatMap(_.redditVideo).map(video => MediaContent(url = parseMediaUrl(video.fallbackUrl),
        width = Some(video.width), height = Some(video.height), medium = Some(MediaMedium.VIDEO)))
      val thumbnail = post.preview.flatMap(_.images).flatMap(_.headOption).map(img => MediaThumbnail(
        url = parseMediaUrl(img.source.url), width = Some(img.source.width), height = Some(img.source.height)))
      val thumbnails = thumbnail.map(Seq(_)).getOrElse(Seq())
      content.map(c => Seq(MediaGroup(content = c, thumbnails = thumbnails)))
    } else if (post.galleryData.isDefined) {
      val groups = post.galleryData.get.items.map { galleryItem =>
        post.mediaMetadata.flatMap(_.get(galleryItem.mediaId)).flatMap {metadata =>
          if (metadata.s.flatMap(_.u).isEmpty) {
            None
          } else {
            Some(MediaGroup(content = MediaContent(
              url = parseMediaUrl(metadata.s.get.u.get),
              width = Some(metadata.s.get.x),
              height = Some(metadata.s.get.y),
              typ = metadata.m,
              medium = metadata.e.map(_.toLowerCase()),
            )))
          }
        }
      }.filter(_.isDefined).map(_.get)
      if (groups.nonEmpty) {
        Some(groups)
      } else {
        None
      }
    } else {
      None
    }

    val article = Article(
      id = ArticleID(source.id, guid),
      title = decodeRedditHtml(post.title),
      sourceID = source.id,
      sourceTitle = source.title,
      guid = guid,
      link = htmlUrl,
      createdAt = ZonedDateTime.now().withNano(0),
      postedAt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(post.created * 1000), ZoneId.systemDefault()),
      description = content.substring(0, Math.min(256, content.length)),
      author = Some(post.author),
      comments = Some(post.numComments),
      upVotes = Some(post.ups),
      downVotes = Some(post.downs),
      mediaGroups = groups.map(MediaGroups),
      nsfw = post.over_18,
    )
    FullArticle(
      article = article.getArticleWithScore,
      content = content,
    )
  }

}
