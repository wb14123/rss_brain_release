package me.binwang.rss.dao

import cats.effect.IO
import me.binwang.rss.model.{Article, SearchOptions, TermWeight}

import java.time.ZonedDateTime

trait ArticleSearchDao {

  def searchForUser(userID: String, searchOptions: SearchOptions): fs2.Stream[IO, Article]
  def searchInFolder(folderID: String, searchOptions: SearchOptions): fs2.Stream[IO, Article]
  def searchInSource(sourceID: String, searchOptions: SearchOptions): fs2.Stream[IO, Article]
  def getTermVector(articleID: String, size: Int): IO[Seq[TermWeight]]
  def updateTitleEmbedding(articleID: String, embedding: Seq[Double]): IO[Boolean]
  def getTitleEmbedding(articleID: String): IO[Option[Seq[Double]]]
  def moreLikeThisForUser(articleID: String, userID: String, start: Int, limit: Int,
    postedBefore: Option[ZonedDateTime], postedAfter: Option[ZonedDateTime]): fs2.Stream[IO, Article]
  def moreLikeThisInFolder(articleID: String, folderID: String, start: Int, limit: Int,
    postedBefore: Option[ZonedDateTime], postedAfter: Option[ZonedDateTime]): fs2.Stream[IO, Article]
  def moreLikeThisInSource(articleID: String, sourceID: String, start: Int, limit: Int,
    postedBefore: Option[ZonedDateTime], postedAfter: Option[ZonedDateTime]): fs2.Stream[IO, Article]

}
