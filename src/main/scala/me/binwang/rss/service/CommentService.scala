package me.binwang.rss.service

import cats.effect.IO
import me.binwang.archmage.core.CatsMacros.timed
import me.binwang.rss.dao.ArticleDao
import me.binwang.rss.fetch.crawler.UrlValidator
import me.binwang.rss.metric.TimeMetrics
import me.binwang.rss.model.ID.ID
import me.binwang.rss.model._
import sttp.client4.Backend

/**
 * APIs related to comments of articles.
 */
class CommentService(
    private val articleDao: ArticleDao,
    private val authorizer: Authorizer,
    sttpBackend: Backend[IO],
    baseUrl: Option[String],
    urlValidator: UrlValidator,
) extends TimeMetrics {

  private val opengardenClient = new OpengardenClient(sttpBackend, baseUrl, urlValidator)

  /**
   * Get comments of an article.
   */
  def getComments(token: String, articleID: ID): IO[ArticleComments] = timed {
    authorizer.authorizeRead(token).flatMap { _ =>
      articleDao.get(articleID).flatMap {
        case None => IO.raiseError(ArticleNotFound(articleID))
        case Some(article) =>
          article.commentUrl match {
            case None => IO.raiseError(CommentUrlMissing(articleID))
            case Some(commentUrl) => opengardenClient.getComments(commentUrl)
          }
      }
    }
  }

}
