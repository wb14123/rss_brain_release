package me.binwang.rss.dao

import cats.effect.IO
import me.binwang.rss.model.{Article, ArticleWithUserMarking}
import me.binwang.rss.model.ID.ID

import java.time.ZonedDateTime

trait ArticleDao {
  def createTable(): IO[Unit]
  def dropTable(): IO[Unit]
  def get(id: ID): IO[Option[Article]]
  def listBySource(sourceID: ID, size: Int = 10, postedBefore: ZonedDateTime, articleID: ID = ""
      ): fs2.Stream[IO, Article]
  def listBySourceWithUserMarking(sourceID: ID, size: Int = 10, postedBefore: ZonedDateTime, articleID: ID,
      userID: String, read: Option[Boolean], bookmarked: Option[Boolean],
      deleted: Option[Boolean] = Some(false)): fs2.Stream[IO, ArticleWithUserMarking]
  def listBySourceOrderByScoreWithUserMarking(sourceID: ID, size: Int = 10, maxScore: Double = Double.MaxValue,
      articleID: ID = "", userID: String, read: Option[Boolean], bookmarked: Option[Boolean],
      deleted: Option[Boolean] = Some(false)): fs2.Stream[IO, ArticleWithUserMarking]
  def listByFolder(folderID: String, size: Int = 10, postedBefore: ZonedDateTime,
      articleID: ID = ""): fs2.Stream[IO, Article]
  def listByFolderWithUserMarking(folderID: String, size: Int, postedBefore: ZonedDateTime, articleID: ID,
      userID: String, read: Option[Boolean] = None, bookmarked: Option[Boolean] = None,
      deleted: Option[Boolean] = Some(false)): fs2.Stream[IO, ArticleWithUserMarking]
  def listByFolderOrderByScoreWithUserMarking(folderID: String, size: Int = 10, maxScore: Double = Double.MaxValue,
      articleID: ID = "", userID: String, read: Option[Boolean], bookmarked: Option[Boolean],
      deleted: Option[Boolean] = Some(false)): fs2.Stream[IO, ArticleWithUserMarking]
  def listByUser(userID: String, size: Int, postedBefore: ZonedDateTime, articleID: ID = ""
      ): fs2.Stream[IO, Article]
  def listByUserWithUserMarking(userID: String, size: Int = 10, postedBefore: ZonedDateTime, articleID: ID,
      read: Option[Boolean], bookmarked: Option[Boolean],
      deleted: Option[Boolean] = Some(false)): fs2.Stream[IO, ArticleWithUserMarking]
  def insertOrUpdate(article: Article): IO[Boolean]
  def deleteAll(): IO[Unit]
}
