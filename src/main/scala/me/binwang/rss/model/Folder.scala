package me.binwang.rss.model

import me.binwang.rss.model.ArticleListLayout.ArticleListLayout
import me.binwang.rss.model.ArticleOrder.ArticleOrder

case class Folder(
  id: String,
  userID: String,
  name: String,
  description: Option[String],
  position: Long,
  count: Int,
  isUserDefault: Boolean = false,
  searchEnabled: Boolean = false,
  searchTerm: Option[String] = None,
  expanded: Boolean = true,
  articleOrder: ArticleOrder = ArticleOrder.TIME,
  recommend: Boolean = false,
  language: Option[String] = None,
  articleListLayout: ArticleListLayout = ArticleListLayout.LIST,
)

case class FolderCreator(
  name: String,
  description: Option[String],
  position: Long,
)

case class FolderUpdater(
  name: Option[String] = None,
  description: Option[Option[String]] = None,
  position: Option[Long] = None,
  searchEnabled: Option[Boolean] = None,
  searchTerm: Option[Option[String]] = None,
  expanded: Option[Boolean] = None,
  articleOrder: Option[ArticleOrder] = None,
  recommend: Option[Boolean] = None,
  language: Option[Option[String]] = None,
  articleListLayout: Option[ArticleListLayout] = None,
)
