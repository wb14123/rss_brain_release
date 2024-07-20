package me.binwang.rss.dao

import cats.effect.IO
import me.binwang.rss.model.RedditSession

trait RedditSessionDao {
  def insert(redditSession: RedditSession): IO[Boolean]
  def updateByState(state: String, redditSession: RedditSession): IO[Boolean]
  def updateByRedditUserID(userID: String, redditUserID: String, redditSession: RedditSession): IO[Boolean]
  def getByRedditUserID(userID: String, redditUserID: String): IO[Option[RedditSession]]
  def getByUserID(userID: String): fs2.Stream[IO, RedditSession]
  def getByState(state: String): IO[Option[RedditSession]]
  def deleteByUserID(userID: String): IO[Boolean]
}
