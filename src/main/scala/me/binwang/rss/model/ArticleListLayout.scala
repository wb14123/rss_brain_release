package me.binwang.rss.model

/**
 * Layout for displaying article list. Server only stores this property. It depends on client how to display them
 * based on the value.
 */
object ArticleListLayout extends Enumeration {
  type ArticleListLayout = Value

  /**
   * Show articles in a water flow list.
   */
  val LIST: ArticleListLayout = Value

  /**
   * Show articles in a grid layout. If there is an image or video, show that as well. Mostly used in video or
   * image feeds like Youtube.
   */
  val GRID: ArticleListLayout = Value

  /**
   * Hide title and show author avatar and name first. Much like Twitter.
   */
  val SOCIAL_MEDIA: ArticleListLayout = Value

  /**
   * Show metadata like title, date without showing description in the list.
   */
  val COMPACT: ArticleListLayout = Value
}
