package me.binwang.rss.model

import me.binwang.rss.model.ID.ID

case class ArticleUserMarking(
    articleID: ID,
    userID: String,
    bookmarked: Boolean = false,
    read: Boolean = false,
    deleted: Boolean = false,
)

case class ArticleUserMarkingUpdater(
    bookmarked: Option[Boolean] = None,
    read: Option[Boolean] = None,
    deleted: Option[Boolean] = None,
)

case class ArticleWithUserMarking(
    article: Article,
    userMarking: ArticleUserMarking,
)

case class FullArticleWithUserMarking(
    article: FullArticle,
    userMarking: ArticleUserMarking,
)
