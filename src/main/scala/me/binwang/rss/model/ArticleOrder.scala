package me.binwang.rss.model


/**
 * The order of showing article in list. The server only stores this property. Clients need to request different API
 * based on the value.
 *
 *
 * @see [[Article.postedAt]]
 * @see [[Article.score]]
 */
object ArticleOrder extends Enumeration {
  type ArticleOrder = Value

  /**
   * Sort articles by `postedAt` time.
   */
  val TIME: ArticleOrder = Value

  /**
   * Sort articles by `score` of article.
   */
  val SCORE: ArticleOrder = Value
}
