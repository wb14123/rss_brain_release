package me.binwang.rss.service

import cats.effect.{Clock, IO}
import me.binwang.archmage.core.CatsMacros.timed
import me.binwang.rss.dao.{ArticleContentDao, ArticleDao, ArticleSearchDao, ArticleUserMarkingDao}
import me.binwang.rss.llm.LargeLanguageModel
import me.binwang.rss.metric.TimeMetrics
import me.binwang.rss.model.ID.ID
import me.binwang.rss.model._

import java.time.{ZoneId, ZonedDateTime}

class ArticleService(
  private val articleDao: ArticleDao,
  private val articleContentDao: ArticleContentDao,
  private val articleUserMarkingDao: ArticleUserMarkingDao,
  private val articleSearchDao: ArticleSearchDao,
  private val llm: LargeLanguageModel,
  private implicit val authorizer: Authorizer,
) extends TimeMetrics {

  def getArticlesBySource(token: String, sourceID: ID, size: Int, postedAt: ZonedDateTime, articleID: ID
      ): fs2.Stream[IO, Article] = timed {
    authorizer.authorizeAsStream(token).flatMap { _ =>
      articleDao.listBySource(sourceID, size, postedAt, articleID)
    }
  }

  def searchArticlesBySourceWithUserMarking(token: String, sourceID: ID,
      searchOptions: SearchOptions): fs2.Stream[IO, ArticleWithUserMarking] = timed {
    authorizer.authorizeAsStream(token).flatMap { userSession =>
      val articles = articleSearchDao.searchInSource(sourceID, searchOptions)
      articleUserMarkingDao.getByArticles(articles, userSession.userID)
    }
  }

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

  def moreLikeThisInFolderWithUserMarking(token: String, articleID: ID, folderID: ID, start: Int, limit: Int,
      postedBefore: Option[ZonedDateTime] = None, postedAfter: Option[ZonedDateTime] = None
      ): fs2.Stream[IO, ArticleWithUserMarking] = timed {
    authorizer.checkFolderReadPermissionAsStream(token, folderID).flatMap { userSession =>
      val articles = articleSearchDao.moreLikeThisInFolder(articleID, folderID, start, limit, postedBefore, postedAfter)
      articleUserMarkingDao.getByArticles(articles, userSession.userID)
    }
  }

  def searchAllArticlesWithUserMarking(token: String,
      searchOptions: SearchOptions): fs2.Stream[IO, ArticleWithUserMarking] = timed {
    authorizer.authorizeAsStream(token).flatMap { userSession =>
      val articles = articleSearchDao.searchForUser(userSession.userID, searchOptions)
      articleUserMarkingDao.getByArticles(articles, userSession.userID)
    }
  }

  def moreLikeThisForUserWithUserMarking(token: String, articleID: ID, start: Int, limit: Int,
      postedBefore: Option[ZonedDateTime] = None, postedAfter: Option[ZonedDateTime] = None
      ): fs2.Stream[IO, ArticleWithUserMarking] = timed {
    authorizer.authorizeAsStream(token).flatMap { userSession =>
      val articles = articleSearchDao.moreLikeThisForUser(articleID, userSession.userID, start, limit,
        postedBefore, postedAfter)
      articleUserMarkingDao.getByArticles(articles, userSession.userID)
    }
  }

  def getArticlesBySourceWithUserMarking(token: String, sourceID: ID, size: Int, postedAt: ZonedDateTime, articleID: ID,
      read: Option[Boolean] = None, bookmarked: Option[Boolean] = None,
      deleted: Option[Boolean] = Some(false)): fs2.Stream[IO, ArticleWithUserMarking] = timed {
    authorizer.authorizeAsStream(token).flatMap { userSession =>
      articleDao.listBySourceWithUserMarking(
        sourceID, size, postedAt, articleID, userSession.userID, read, bookmarked, deleted)
    }
  }

  def getArticlesBySourceOrderByScoreWithUserMarking(token: String, sourceID: ID, size: Int, maxScore: Option[Double],
      articleID: ID, read: Option[Boolean] = None, bookmarked: Option[Boolean] = None,
      deleted: Option[Boolean] = Some(false)): fs2.Stream[IO, ArticleWithUserMarking] = timed {
    authorizer.authorizeAsStream(token).flatMap { userSession =>
      articleDao.listBySourceOrderByScoreWithUserMarking(sourceID, size, maxScore.getOrElse(Double.MaxValue),
        articleID, userSession.userID, read, bookmarked, deleted)
    }
  }

  def getArticlesByFolder(token: String, folderID: ID, size: Int, postedBefore: ZonedDateTime, articleID: ID
      ): fs2.Stream[IO, Article] = timed {
    authorizer.checkFolderPermissionAsStream(token, folderID).flatMap(_ =>
      articleDao.listByFolder(folderID, size, postedBefore, articleID)
    )
  }

  def getArticlesByFolderWithUserMarking(token: String, folderID: ID, size: Int, postedBefore: ZonedDateTime,
      articleID: ID, read: Option[Boolean] = None, bookmarked: Option[Boolean] = None,
      deleted: Option[Boolean] = Some(false)): fs2.Stream[IO, ArticleWithUserMarking] = timed {
    authorizer.checkFolderPermissionAsStream(token, folderID).flatMap(session =>
      articleDao.listByFolderWithUserMarking(
        folderID, size, postedBefore, articleID, session.userID, read, bookmarked, deleted)
    )
  }

  def getArticlesByFolderOrderByScoreWithUserMarking(token: String, folderID: ID, size: Int, maxScore: Option[Double],
      articleID: ID, read: Option[Boolean] = None, bookmarked: Option[Boolean] = None,
      deleted: Option[Boolean] = Some(false)): fs2.Stream[IO, ArticleWithUserMarking] = timed {
    authorizer.checkFolderPermissionAsStream(token, folderID).flatMap(session =>
      articleDao.listByFolderOrderByScoreWithUserMarking(folderID, size, maxScore.getOrElse(Double.MaxValue),
        articleID, session.userID, read, bookmarked, deleted)
    )
  }

  def getMyArticles(token: String, size: Int, postedAt: ZonedDateTime, articleID: ID): fs2.Stream[IO, Article] = timed {
    authorizer.authorizeAsStream(token).flatMap { session =>
      articleDao.listByUser(session.userID, size, postedAt, articleID)
    }
  }

  def getMyArticlesWithUserMarking(token: String, size: Int, postedAt: ZonedDateTime, articleID: ID,
      read: Option[Boolean] = None, bookmarked: Option[Boolean] = None,
      deleted: Option[Boolean] = Some(false)): fs2.Stream[IO, ArticleWithUserMarking] = timed {
    authorizer.authorizeAsStream(token).flatMap { session =>
      articleDao.listByUserWithUserMarking(session.userID, size, postedAt, articleID, read, bookmarked, deleted)
    }
  }

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

  def batchGetArticleContent(token: String, articleIDs: ArticleIDs): fs2.Stream[IO, ArticleContent] = timed {
    authorizer.authorizeAsStream(token).flatMap(_ =>
      articleContentDao.batchGet(articleIDs.ids))
  }

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

  def getFolderRecommendSearchTerms(token: String, folderID: String, articleSize: Int,
      likedArticlesPostedAfter: ZonedDateTime, resultSize: Int): IO[SearchTerms] = timed {
    for {
      session <- authorizer.checkFolderPermission(token, folderID).map(_._1)
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
      titles = (recentLiked ++ moreArticles).map(_.article.title)
      searches <- llm.getRecommendSearchQueries(titles, resultSize)
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

  def getArticleTermVector(token: String, articleID: ID, size: Int): IO[TermWeights] = timed {
    authorizer.authorize(token).flatMap( _ => articleSearchDao.getTermVector(articleID, size).map(TermWeights))
  }

}
