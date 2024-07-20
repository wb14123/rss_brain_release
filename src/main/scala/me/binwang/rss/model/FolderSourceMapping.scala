package me.binwang.rss.model

import me.binwang.rss.model.ArticleListLayout.ArticleListLayout
import me.binwang.rss.model.ArticleOrder.ArticleOrder

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

case class FolderSourceMappingUpdater(
  customSourceName: Option[Option[String]] = None,
  showTitle: Option[Boolean] = None,
  showFullArticle: Option[Boolean] = None,
  showMedia: Option[Boolean] = None,
  articleOrder: Option[ArticleOrder] = None,
  articleListLayout: Option[ArticleListLayout] = None,
)
