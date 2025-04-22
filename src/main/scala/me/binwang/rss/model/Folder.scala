package me.binwang.rss.model

import me.binwang.rss.model.ArticleListLayout.ArticleListLayout
import me.binwang.rss.model.ArticleOrder.ArticleOrder

/**
 * User can create folders that contain multiple sources.
 *
 * @param id Folder ID.
 * @param userID Which user has the ownership of the folder.
 * @param name Folder name.
 * @param description Folder description.
 * @param position The position of the folder when displaying.
 * @param count How many sources are in folder (not in use, always 0 for now).
 * @param isUserDefault RSS Brain will create a folder for user by default when creating a user. This is like the root
 *                      folder where sources without an explict folder belongs to. If this field is true, it means this
 *                      is the root folder for this user.
 * @param searchEnabled If articles filer is enabled based on search term.
 * @param searchTerm The search term to filter articles when get article list.
 * @param expanded If show this folder as expanded in clients.
 * @param recommend If true, this folder will be shown in "Explore" page.
 */
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

/**
 * The structure to create a folder. Only contains fields that user can set.
 *
 * @param position The position of the folder. If insert at the last place, it should be the last position + 1000.
 *                 Otherwise, it should be the avg value of the position before and after it. If there is no number
 *                 available, call FolderService.cleanupPosition to make the gap larger between folders.
 *
 * @see [[me.binwang.rss.service.FolderService.cleanupPosition()]]
 */
case class FolderCreator(
  name: String,
  description: Option[String],
  position: Long,
)


/**
 * Structure to update the folder. None means don't update that field.
 */
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
