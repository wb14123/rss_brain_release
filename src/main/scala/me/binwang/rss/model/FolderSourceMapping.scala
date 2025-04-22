package me.binwang.rss.model

import me.binwang.rss.model.ArticleListLayout.ArticleListLayout
import me.binwang.rss.model.ArticleOrder.ArticleOrder

/**
 * Source data associated to a folder. Currently, this is kind of used as user's config for a source.
 *
 * @param position The position of a source in folder.
 * @param customSourceName The name to display in folder instead of the title parsed from the feed.
 * @param showTitle Deprecated. Use `articleListLayout`.
 * @param showFullArticle Deprecated. Use `articleListLayout`.
 * @param showMedia Deprecated. Use `articleListLayout`.
 */
case class FolderSourceMapping(
  folderID: String,
  sourceID: String,
  userID: String,
  position: Long,
  customSourceName: Option[String] = None,
  showTitle: Boolean = true,
  showFullArticle: Boolean = false,
  showMedia: Boolean = false,
  articleOrder: ArticleOrder = ArticleOrder.TIME,
  articleListLayout: ArticleListLayout = ArticleListLayout.LIST,
)

/**
 * Structure to update FolderSourceMapping. None means don't update that field.
 */
case class FolderSourceMappingUpdater(
  customSourceName: Option[Option[String]] = None,
  showTitle: Option[Boolean] = None,
  showFullArticle: Option[Boolean] = None,
  showMedia: Option[Boolean] = None,
  articleOrder: Option[ArticleOrder] = None,
  articleListLayout: Option[ArticleListLayout] = None,
)
