package me.binwang.rss.service

import cats.effect.{Clock, IO}
import me.binwang.archmage.core.CatsMacros.timed
import me.binwang.rss.dao._
import me.binwang.rss.llm.LLMModels
import me.binwang.rss.metric.TimeMetrics
import me.binwang.rss.model.ID.ID
import me.binwang.rss.model._

import java.time.{ZoneId, ZonedDateTime}


/**
 * APIs related to articles.
 */
class ArticleService(
  private val articleDao: ArticleDao,
  private val articleContentDao: ArticleContentDao,
  private val articleUserMarkingDao: ArticleUserMarkingDao,
  private val articleSearchDao: ArticleSearchDao,
  private val userDao: UserDao,
  private val llmModels: LLMModels,
  private implicit val authorizer: Authorizer,
) extends TimeMetrics {

  /**
   * Get articles by source.
   *
   * @param size How many articles to get at most.
   * @param postedBefore Filter articles that is posted before this time. Default is 0 which means no article will
   *                     be returned if this is not specified. For the first request of the pagination, it's better
   *                     to use the current timestamp (in milliseconds) or a very large number that is larger than
   *                     the current timestamp.
   * @param articleID Filter articles that is larger than this if the article's postedAt equals postedBefore in param.
   */
  def getArticlesBySource(token: String, sourceID: ID, size: Int, postedBefore: ZonedDateTime, articleID: ID
      ): fs2.Stream[IO, Article] = timed {
    authorizer.authorizeAsStream(token).flatMap { _ =>
      articleDao.listBySource(sourceID, size, postedBefore, articleID)
    }
  }

  def searchArticlesBySourceWithUserMarking(token: String, sourceID: ID,
      searchOptions: SearchOptions): fs2.Stream[IO, ArticleWithUserMarking] = timed {
    authorizer.authorizeAsStream(token).flatMap { userSession =>
      val articles = articleSearchDao.searchInSource(sourceID, searchOptions)
      articleUserMarkingDao.getByArticles(articles, userSession.userID)
    }
  }

  /**
   * Get more articles like this one in a specific source. Return articles with user marking.
   *
   * @param articleID The article's ID to search for more like this articles.
   * @param sourceID Which source to search for more like this articles.
   * @param start The number of articles to skip.
   * @param limit How many articles to return at most.
   * @param postedBefore Filter on the articles that is posted before this time.
   * @param postedAfter Filter on the articles that is posted after this time.
   */
  def moreLikeThisInSourceWithUserMarking(token: String, articleID: ID, sourceID: ID, start: Int, limit: Int,
      postedBefore: Option[ZonedDateTime] = None, postedAfter: Option[ZonedDateTime] = None
      ): fs2.Stream[IO, ArticleWithUserMarking] = timed {
    authorizer.authorizeAsStream(token).flatMap { userSession =>
      val articles = articleSearchDao.moreLikeThisInSource(articleID, sourceID, start, limit, postedBefore, postedAfter)
      articleUserMarkingDao.getByArticles(articles, userSession.userID)
    }
  }

  def searchArticlesByFolderWithUserMarking(token: String, folderID: ID,
      searchOptions: SearchOptions): fs2.Stream[IO, ArticleWithUserMarking] = timed {
    authorizer.checkFolderReadPermissionAsStream(token, folderID).flatMap { userSession =>
      val articles = articleSearchDao.searchInFolder(folderID, searchOptions)
      articleUserMarkingDao.getByArticles(articles, userSession.userID)
    }
  }

  /**
   * Get more articles like this one in a specific folder. Return articles with user marking.
   *
   * @param articleID The article's ID to search for more like this articles.
   * @param folderID Which folder to search for articles like this.
   * @param start How many articles to skip.
   * @param limit How many articles to return at most.
   * @param postedBefore Filter articles that is posted before this time.
   * @param postedAfter Filter articles that is posted after this time.
   */
  def moreLikeThisInFolderWithUserMarking(token: String, articleID: ID, folderID: ID, start: Int, limit: Int,
      postedBefore: Option[ZonedDateTime] = None, postedAfter: Option[ZonedDateTime] = None
      ): fs2.Stream[IO, ArticleWithUserMarking] = timed {
    authorizer.checkFolderReadPermissionAsStream(token, folderID).flatMap { userSession =>
      val articles = articleSearchDao.moreLikeThisInFolder(articleID, folderID, start, limit, postedBefore, postedAfter)
      articleUserMarkingDao.getByArticles(articles, userSession.userID)
    }
  }

  /**
   * Search articles in all sources that the user has subscribed.
   */
  def searchAllArticlesWithUserMarking(token: String,
      searchOptions: SearchOptions): fs2.Stream[IO, ArticleWithUserMarking] = timed {
    authorizer.authorizeAsStream(token).flatMap { userSession =>
      val articles = articleSearchDao.searchForUser(userSession.userID, searchOptions)
      articleUserMarkingDao.getByArticles(articles, userSession.userID)
    }
  }

  /**
   * Get more articles like this one. Search in all the articles that the user has subscribed.
   * Return articles with user marking.
   *
   * @param articleID The article's ID to search for more like this articles.
   * @param start How many articles to skip.
   * @param limit How many articles to return at most.
   * @param postedBefore Filter articles that is posted before this time.
   * @param postedAfter Filter articles that is posted after this time.
   */
  def moreLikeThisForUserWithUserMarking(token: String, articleID: ID, start: Int, limit: Int,
      postedBefore: Option[ZonedDateTime] = None, postedAfter: Option[ZonedDateTime] = None
      ): fs2.Stream[IO, ArticleWithUserMarking] = timed {
    authorizer.authorizeAsStream(token).flatMap { userSession =>
      val articles = articleSearchDao.moreLikeThisForUser(articleID, userSession.userID, start, limit,
        postedBefore, postedAfter)
      articleUserMarkingDao.getByArticles(articles, userSession.userID)
    }
  }

  /**
   * Get articles in a source. Return articles with user marking.
   *
   * @param size How many articles to return at most.
   * @param postedBefore Filter articles that is posted before or equal to this time. Default is 0 which means no article will
   *    be returned if this is not specified. For the first request of the pagination, it's better
   *    to use the current timestamp (in milliseconds) or a very large number that is larger than
   *    the current timestamp.
   * @param articleID If there are articles posted at the same time as `postedBefore`, return the articles that ID is larger
   *                  than this.
   * @param read Filter on [[ArticleWithUserMarking.userMarking.read]]. None means don't filter on this field.
   * @param bookmarked Filter on [[ArticleWithUserMarking.userMarking.bookmarked]]. None means don't filter on this field.
   * @param deleted Filter on [[ArticleWithUserMarking.userMarking.deleted]]. False by default. None means don't filter on this field.
   */
  def getArticlesBySourceWithUserMarking(token: String, sourceID: ID, size: Int, postedBefore: ZonedDateTime,
      articleID: ID, read: Option[Boolean] = None, bookmarked: Option[Boolean] = None,
      deleted: Option[Boolean] = Some(false)): fs2.Stream[IO, ArticleWithUserMarking] = timed {
    authorizer.authorizeAsStream(token).flatMap { userSession =>
      articleDao.listBySourceWithUserMarking(
        sourceID, size, postedBefore, articleID, userSession.userID, read, bookmarked, deleted)
    }
  }

  /**
   * Like [[ArticleService.getArticlesBySourceWithUserMarking]] but order by score instead of time.
   *
   * @param maxScore Filter articles that has a score less or equal to this.
   * @param articleID If there are articles have a score equal to `maxScore`, only return articles with ID lager than this.
   */
  def getArticlesBySourceOrderByScoreWithUserMarking(token: String, sourceID: ID, size: Int, maxScore: Option[Double],
      articleID: ID, read: Option[Boolean] = None, bookmarked: Option[Boolean] = None,
      deleted: Option[Boolean] = Some(false)): fs2.Stream[IO, ArticleWithUserMarking] = timed {
    authorizer.authorizeAsStream(token).flatMap { userSession =>
      articleDao.listBySourceOrderByScoreWithUserMarking(sourceID, size, maxScore.getOrElse(Double.MaxValue),
        articleID, userSession.userID, read, bookmarked, deleted)
    }
  }

  /**
   * Like [[ArticleService.getArticlesBySource]] but for getting articles in a folder.
   */
  def getArticlesByFolder(token: String, folderID: ID, size: Int, postedBefore: ZonedDateTime, articleID: ID
      ): fs2.Stream[IO, Article] = timed {
    authorizer.checkFolderPermissionAsStream(token, folderID).flatMap(_ =>
      articleDao.listByFolder(folderID, size, postedBefore, articleID)
    )
  }

  /**
   * Like [[ArticleService.getArticlesBySourceWithUserMarking]] but for getting articles in a folder.
   */
  def getArticlesByFolderWithUserMarking(token: String, folderID: ID, size: Int, postedBefore: ZonedDateTime,
      articleID: ID, read: Option[Boolean] = None, bookmarked: Option[Boolean] = None,
      deleted: Option[Boolean] = Some(false)): fs2.Stream[IO, ArticleWithUserMarking] = timed {
    authorizer.checkFolderPermissionAsStream(token, folderID).flatMap(session =>
      articleDao.listByFolderWithUserMarking(
        folderID, size, postedBefore, articleID, session.userID, read, bookmarked, deleted)
    )
  }

  /**
   * Like [[ArticleService.getArticlesBySourceOrderByScoreWithUserMarking]] bur for getting articles in a folder.
   */
  def getArticlesByFolderOrderByScoreWithUserMarking(token: String, folderID: ID, size: Int, maxScore: Option[Double],
      articleID: ID, read: Option[Boolean] = None, bookmarked: Option[Boolean] = None,
      deleted: Option[Boolean] = Some(false)): fs2.Stream[IO, ArticleWithUserMarking] = timed {
    authorizer.checkFolderPermissionAsStream(token, folderID).flatMap(session =>
      articleDao.listByFolderOrderByScoreWithUserMarking(folderID, size, maxScore.getOrElse(Double.MaxValue),
        articleID, session.userID, read, bookmarked, deleted)
    )
  }

  /**
   * Like [[ArticleService.getArticlesBySource]] but for getting all the articles that the user has subscribed.
   */
  def getMyArticles(token: String, size: Int, postedBefore: ZonedDateTime, articleID: ID): fs2.Stream[IO, Article] = timed {
    authorizer.authorizeAsStream(token).flatMap { session =>
      articleDao.listByUser(session.userID, size, postedBefore, articleID)
    }
  }

  /**
   * Like [[ArticleService.getArticlesBySourceWithUserMarking]] but for getting all the articles that the user has
   * subscribed.
   */
  def getMyArticlesWithUserMarking(token: String, size: Int, postedBefore: ZonedDateTime, articleID: ID,
      read: Option[Boolean] = None, bookmarked: Option[Boolean] = None,
      deleted: Option[Boolean] = Some(false)): fs2.Stream[IO, ArticleWithUserMarking] = timed {
    authorizer.authorizeAsStream(token).flatMap { session =>
      articleDao.listByUserWithUserMarking(session.userID, size, postedBefore, articleID, read, bookmarked, deleted)
    }
  }

  /**
   * Get a full article given an article ID.
   */
  def getFullArticle(token: String, articleID: ID): IO[FullArticle] = timed {
    (for {
      _ <- authorizer.authorize(token)
      article <- articleDao.get(articleID)
      content <- articleContentDao.get(articleID)
    } yield (article, content)) map {
      case (Some(a), Some(c)) => FullArticle(a, c.content)
      case _ => throw ArticleNotFound(articleID)
    }
  }

  /**
   * Get content for multiple articles. Useful when rendering an article list that needs to show full content.
   */
  def batchGetArticleContent(token: String, articleIDs: ArticleIDs): fs2.Stream[IO, ArticleContent] = timed {
    authorizer.authorizeAsStream(token).flatMap(_ =>
      articleContentDao.batchGet(articleIDs.ids))
  }

  /**
   * Get full article with user marking given an article ID.
   */
  def getFullArticleWithUserMarking(token: String, articleID: ID): IO[FullArticleWithUserMarking] = timed {
    (for {
      session <- authorizer.authorize(token)
      article <- articleDao.get(articleID)
      content <- articleContentDao.get(articleID)
      userMarking <- articleUserMarkingDao.get(articleID, session.userID)
    } yield (article, content, userMarking)) map {
      case (Some(a), Some(c), marking) => FullArticleWithUserMarking(FullArticle(a, c.content), marking)
      case _ => throw ArticleNotFound(articleID)
    }
  }

  /**
   * Let the system come up with some search terms that can be used in search engines, so that they can search for more
   * content that is similar to the articles in this folder.
   *
   * @param folderID Which folder the recommendation is based on.
   * @param articleSize How many articles to look into for recommendation. It will get liked articles first, and if the
   *                    liked articles is fewer than `articleSize`, it will find from most recent articles.
   * @param likedArticlesPostedAfter Only use liked articles posted after this time so it doesn't go too back into
   *                                 user's interest.
   * @param resultSize How many search terms to return.
   */
  def getFolderRecommendSearchTerms(token: String, folderID: String, articleSize: Int,
      likedArticlesPostedAfter: ZonedDateTime, resultSize: Int): IO[SearchTerms] = timed {
    for {
      session <- authorizer.checkFolderPermission(token, folderID).map(_._1)
      user <- userDao.getByID(session.userID)
      llmApiKey = user.flatMap(_.llmApiKey)
      llmModel = user.flatMap(_.llmEngine).map(llmModels.getModel)
      _ <- if (llmApiKey.isEmpty || llmModel.isEmpty) {
          IO.raiseError(LLMEngineNotConfigured(user.map(_.id).getOrElse("")))
        } else IO.pure()
      nowInstant <- Clock[IO].realTimeInstant
      now = ZonedDateTime.ofInstant(nowInstant, ZoneId.systemDefault())
      recentLiked <- articleDao.listByFolderWithUserMarking(folderID, articleSize,
          now, "", session.userID, bookmarked = Some(true))
        .filter(a => a.article.postedAt.isAfter(likedArticlesPostedAfter))
        .compile.toList
      moreArticles <- if (recentLiked.size >= articleSize) {
        IO.pure(Seq())
      } else {
        articleDao.listByFolderWithUserMarking(folderID, articleSize - recentLiked.size,
          now, "", session.userID, bookmarked = Some(false)).compile.toList
      }
      articles = (recentLiked ++ moreArticles).map(_.article)
      searches <- llmModel.get.getRecommendSearchQueries(articles, resultSize, llmApiKey.get)
    } yield SearchTerms(searches)
  }

  def readArticle(token: String, articleID: ID): IO[Boolean] = timed {
    authorizer.authorize(token).flatMap { session =>
      articleUserMarkingDao.updateMarking(articleID, session.userID, ArticleUserMarkingUpdater(read = Some(true)))
    }
  }

  def unreadArticle(token: String, articleID: ID): IO[Boolean] = timed {
    authorizer.authorize(token).flatMap { session =>
      articleUserMarkingDao.updateMarking(articleID, session.userID, ArticleUserMarkingUpdater(read = Some(false)))
    }
  }

  def bookmarkArticle(token: String, articleID: ID): IO[Boolean] = timed {
    authorizer.authorize(token).flatMap { session =>
      articleUserMarkingDao.updateMarking(articleID, session.userID, ArticleUserMarkingUpdater(bookmarked = Some(true)))
    }
  }

  def unBookmarkArticle(token: String, articleID: ID): IO[Boolean] = timed {
    authorizer.authorize(token).flatMap { session =>
      articleUserMarkingDao.updateMarking(articleID, session.userID, ArticleUserMarkingUpdater(bookmarked = Some(false)))
    }
  }

  def markArticleAsDeleted(token: String, articleID: ID): IO[Boolean] = timed {
    authorizer.authorize(token).flatMap { session =>
      articleUserMarkingDao.updateMarking(articleID, session.userID, ArticleUserMarkingUpdater(deleted = Some(true)))
    }
  }

  def markArticleAsNotDeleted(token: String, articleID: ID): IO[Boolean] = timed {
    authorizer.authorize(token).flatMap { session =>
      articleUserMarkingDao.updateMarking(articleID, session.userID, ArticleUserMarkingUpdater(deleted = Some(false)))
    }
  }

  def markArticleReadProgress(token: String, articleID: ID, progress: Int): IO[Boolean] = timed {
    authorizer.authorize(token).flatMap { session =>
      articleUserMarkingDao.updateMarking(articleID, session.userID, ArticleUserMarkingUpdater(readProgress = Some(progress)))
    }
  }

  /**
   * Get a term vector for an article.
   * @param size The max size of the term vector.
   * @return a term vector that has all the terms in the article and associated weight. It is calculated by Elasticsearch.
   *         See https://en.wikipedia.org/wiki/Tf%E2%80%93idf for more details about how it is calculated.
   */
  def getArticleTermVector(token: String, articleID: ID, size: Int): IO[TermWeights] = timed {
    authorizer.authorize(token).flatMap( _ => articleSearchDao.getTermVector(articleID, size).map(TermWeights))
  }

}
