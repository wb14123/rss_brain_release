package me.binwang.rss.parser

import io.circe.generic.extras._
import io.circe.parser
import me.binwang.rss.model._

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.time.{Instant, ZoneId, ZonedDateTime}
import scala.concurrent.duration.DurationInt
import scala.util.Try

// parser for https://hn.algolia.com/api/v1/search?tags=front_page
object HackerNewsParser {

  implicit val config: Configuration = Configuration.default.withSnakeCaseMemberNames

  @ConfiguredJsonCodec case class HackerNewsSearchHits(
      hits: Seq[HackerNewsStory]
  )

  @ConfiguredJsonCodec case class HackerNewsStory(
      @JsonKey("objectID") objectID: String,
      title: String,
      url: Option[String],
      author: String,
      points: Int,
      numComments: Option[Int],
      storyText: Option[String],
      createdAtI: Long,
  )

  def hackerNewsSource(url: String, now: ZonedDateTime): Source = Source(
    SourceID(url),
    xmlUrl = url,
    title = Some("Hacker News Top Stories"),
    htmlUrl = Some("https://news.ycombinator.com/news"),
    description = Some("Hacker News top stories powered by Algolia API"),
    articleOrder = ArticleOrder.SCORE, // sort by score by default
    iconUrl = Some("https://news.ycombinator.com/favicon.ico"),
    fetchDelayMillis = 10.minutes.toMillis, // fetch every 10 minutes
    updatedAt = now,
    importedAt = now,
    fetchScheduledAt = now,
  )

  def parse(url: String, content: InputStream, now: ZonedDateTime): (Source, Seq[Try[FullArticle]]) = {
    val contentStr = new String(content.readAllBytes(), StandardCharsets.UTF_8)
    val source = hackerNewsSource(url, now)
    parser.parse(contentStr).flatMap(_.as[HackerNewsSearchHits]) match {
      case Left(e) => throw e
      case Right(result) =>
        val articles = result.hits.map(storyToArticle(source, _))
        (source, articles)
    }
  }

  private def storyToArticle(source: Source, story: HackerNewsStory): Try[FullArticle] = {
    val guid = story.objectID;
    val htmlUrl = "https://news.ycombinator.com/item?id=" + guid
    val content = if (story.storyText.isDefined) {
      story.storyText.get
    } else {
      s"<a href='${story.url.get}' target='_blank' rel='noreferrer noopener'>${story.url.get}</a>"
    }
    val article = Article(
      id = ArticleID(source.id, guid),
      title = story.title,
      sourceID = source.id,
      sourceTitle = source.title,
      guid = guid,
      link = htmlUrl,
      createdAt = ZonedDateTime.now().withNano(0),
      postedAt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(story.createdAtI * 1000), ZoneId.systemDefault()),
      description = content.substring(0, Math.min(256, content.length)),
      author = Some(story.author),
      comments = Some(story.numComments.getOrElse(0)),
      upVotes = Some(story.points),
    )
    Try(FullArticle(
      article = article.getArticleWithScore,
      content = content,
    ))
  }


}
