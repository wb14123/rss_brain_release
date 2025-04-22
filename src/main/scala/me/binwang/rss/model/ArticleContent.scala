package me.binwang.rss.model

import me.binwang.rss.model.ID.ID

/**
 * The structure for the content of article.
 * @param id Article ID.
 * @param content The content of the article.
 */
case class ArticleContent(
  id: ID,
  content: String,
)
