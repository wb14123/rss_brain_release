package me.binwang.rss.dao

import cats.effect.IO
import me.binwang.rss.model.{Article, ArticleUserMarking, ArticleUserMarkingUpdater, ArticleWithUserMarking}
import me.binwang.rss.model.ID.ID

trait ArticleUserMarkingDao {
  def get(articleID: ID, userID: String): IO[ArticleUserMarking]
  def updateMarking(articleId: ID, userId: String, updater: ArticleUserMarkingUpdater): IO[Boolean]
  def getByArticles(articles: fs2.Stream[IO, Article], userID : String): fs2.Stream[IO, ArticleWithUserMarking]
  def deleteAllForUser(userId: String): IO[Long]
}
