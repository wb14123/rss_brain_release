package me.binwang.rss.model

import me.binwang.rss.model.ID.ID

case class ArticleUserMarking(
    articleID: ID,
    userID: String,
    bookmarked: Boolean = false,
    read: Boolean = false,
    deleted: Boolean = false,

    /*
     Different kind of article can have different meaning for this. But in general the media based articles like
     video or audio, this means the milliseconds of the audio/video.
     */
    readProgress: Int = 0,
)

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
