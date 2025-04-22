package me.binwang.rss.model

import me.binwang.rss.model.ID.ID

import java.time.ZonedDateTime

object ArticleID {
  def apply(sourceID: ID, guid: String): ID = ID.hash(sourceID + guid)
}

case class ArticleIDs(ids: Seq[ID])

/**
 * Article structure
 * @param id Article ID. It's a hash of source url + guid
 * @param title Article title parsed from feed.
 * @param guid GUID parsed from feed.
 * @param link The link to the original article. Parsed from feed.
 * @param createdAt When this article is created in RSS Brain.
 * @param postedAt When the article is posted. Parsed from feed. If feed doesn't have a post time, fill it with current time when the article is updated.
 * @param description If feed has a description field, use that. Otherwise, copy from content. Has a max length.
 * @param author The author of the article. Parsed from feed.
 * @param comments How many comments for the article.
 * @param upVotes How many up votes for the article.
 * @param downVotes How many down votes for the article.
 * @param score RSS Brain algorithm calculate a score based on up votes and article freshness.
 * @param mediaGroups Medias in the article. Parsed from feed.
 * @param nsfw If the article is not suite for work.
 * @param postedAtIsMissing If `postedAt` is missing in feed and filled by current time.
 */
case class Article(
  id: ID,
  title: String,
  sourceID: ID,
  sourceTitle: Option[String],
  guid: String, // unique id in rss
  link: String,
  createdAt: ZonedDateTime,
  postedAt: ZonedDateTime,
  description: String,
  author: Option[String] = None,
  comments: Option[Int] = None,
  upVotes: Option[Int] = None,
  downVotes: Option[Int] = None,
  score: Double = 0.0,
  mediaGroups: Option[MediaGroups] = None,
  nsfw: Boolean = false,

  /*
   Is postedAt field missing and filled with current time?

   Add this field because:

   1. it's hard to change type postedAt to option now
   2. articles still need postedAt to be ranked
   */
  postedAtIsMissing: Boolean = false,
) {
  // comment out during ID migration
  assert(id == ArticleID(sourceID, guid), "Article id has invalid patter")
  assert(guid.nonEmpty, "Article guid is empty")
  assert(link.nonEmpty, "Article link is empty")

  def getArticleWithScore: Article = {
    val s = if (upVotes.isEmpty && downVotes.isEmpty) {
      if (comments.isEmpty) {
        10 // a default score
      } else {
        comments.get // use comments as score
      }
    } else {
      upVotes.getOrElse(0) - downVotes.getOrElse(0)
    }
    val sign = if (s > 0) 1 else -1
    val voteScore = sign * Math.log10(Math.max(1, Math.abs(s)))
    val timeScore = postedAt.toInstant.toEpochMilli / (12.5 * 3600 * 1000)
    val newScore = voteScore + timeScore
    this.copy(score = newScore)
  }
}
