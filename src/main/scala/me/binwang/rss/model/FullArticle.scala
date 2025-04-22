package me.binwang.rss.model

/**
 * A structure that contents both article metadata and content.
 * @param article Article metadata.
 * @param content The content of article.
 */
case class FullArticle(
  article: Article,
  content: String,
)
