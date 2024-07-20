package me.binwang.rss.dao

import cats.effect.IO
import me.binwang.rss.model.MoreLikeThisMapping
import me.binwang.rss.model.MoreLikeThisType.MoreLikeThisType

trait MoreLikeThisMappingDao {

  def insertIfNotExists(mapping: MoreLikeThisMapping): IO[Boolean] // update position if it's already exists
  def delete(mapping: MoreLikeThisMapping): IO[Boolean]
  def listByFromID(fromID: String, fromType: MoreLikeThisType, userID: String, size: Int,
      startPosition: Long = -1): fs2.Stream[IO, MoreLikeThisMapping]
  def updatePosition(mapping: MoreLikeThisMapping): IO[Boolean]
  def cleanupPosition(fromID: String, fromType: MoreLikeThisType, userID: String): IO[Int]
  def deleteAllForUser(userID: String): IO[Long]

}
