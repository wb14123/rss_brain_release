package me.binwang.rss.dao

import cats.effect.IO
import me.binwang.rss.model.ArticleContent
import me.binwang.rss.model.ID.ID

trait ArticleContentDao {
  def createTable(): IO[Unit]
  def dropTable(): IO[Unit]
  def deleteAll(): IO[Unit]
  def get(id: ID): IO[Option[ArticleContent]]
  def batchGet(ids: Seq[ID]): fs2.Stream[IO, ArticleContent]
  def insertOrUpdate(content: ArticleContent): IO[Boolean]
}
