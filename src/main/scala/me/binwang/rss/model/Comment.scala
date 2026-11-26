package me.binwang.rss.model

import java.time.ZonedDateTime

/**
 * Comments of one article, as returned by the comment source of the article.
 *
 * @param articleUrl The URL of the article the comments belong to.
 * @param comments Top level comments.
 * @param hasMore If the source page has a control to load more top level comments.
 */
case class ArticleComments(
  articleUrl: String,
  comments: Seq[Comment],
  hasMore: Boolean,
)

/**
 * A comment and its replies, nested to any depth.
 *
 * @param id The comment's ID in the source site.
 * @param body The comment content as raw HTML from the source site. Not sanitized.
 * @param author The author's display name.
 * @param authorUrl The URL of the author's profile.
 * @param score The score of the comment, if the source site publishes one.
 * @param createdAt When the comment is posted.
 * @param permalink The URL of the comment in the source site.
 * @param hasMoreReplies If the source page has a control to load more replies of this comment.
 * @param replies Replies of this comment.
 */
case class Comment(
  id: String,
  body: String,
  author: Option[String],
  authorUrl: Option[String],
  score: Option[Int],
  createdAt: Option[ZonedDateTime],
  permalink: Option[String],
  hasMoreReplies: Boolean,
  replies: Seq[Comment],
)
