package me.binwang.rss.model

import me.binwang.rss.model.ID.ID

/**
 * User data associated to an article. The server is mostly just store the data. The clients need to display articles
 * based on the value and call APIs with different parameters.
 *
 * @param articleID The ID of article.
 * @param userID The user ID.
 * @param bookmarked If the user is bookmarked (or liked) by a user.
 * @param read If the article is read by the user.
 * @param deleted If the article is deleted by the user.
 * @param readProgress Different kind of article can have different meaning for this. But in general for the media based
 *                     articles like video or audio, this means the milliseconds of the audio/video.
 */
case class ArticleUserMarking(
    articleID: ID,
    userID: String,
    bookmarked: Boolean = false,
    read: Boolean = false,
    deleted: Boolean = false,
    readProgress: Int = 0,
)

/**
 * An updater for `ArticleUserMarking`. None means don't update that field.
 */
case class ArticleUserMarkingUpdater(
    bookmarked: Option[Boolean] = None,
    read: Option[Boolean] = None,
    deleted: Option[Boolean] = None,
    readProgress: Option[Int] = None,
)

case class ArticleWithUserMarking(
    article: Article,
    userMarking: ArticleUserMarking,
)

case class FullArticleWithUserMarking(
    article: FullArticle,
    userMarking: ArticleUserMarking,
)
