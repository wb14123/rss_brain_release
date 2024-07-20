package me.binwang.rss.dao.sql

import cats.effect.IO
import doobie._
import doobie.implicits._
import me.binwang.rss.dao.ArticleContentDao
import me.binwang.rss.model.{ArticleContent, ID}
import me.binwang.rss.model.ID.ID

class ArticleContentSqlDao(implicit val connectionPool: ConnectionPool) extends ArticleContentDao with BaseSqlDao {
  override def table: String = "article_contents"

  import dbCtx._
  private implicit val articleContentSchema: dbCtx.SchemaMeta[ArticleContent] = schemaMeta[ArticleContent]("article_contents")

  override def createTable(): IO[Unit] = {
    Fragment.const(
      s"""create table if not exists $table (
            id char(${ID.maxLength}) primary key,
            content varchar not null)
        """)
      .update
      .run
      .map(_ => ())
      .transact(xa)
  }

  override def get(id: ID): IO[Option[ArticleContent]] = {
    stream(quote {
      query[ArticleContent].filter(_.id == lift(id)).take(1)
    }).transact(xa).compile.last
  }

  override def insertOrUpdate(content: ArticleContent): IO[Boolean] = {
    run(quote {
      query[ArticleContent]
        .insertValue(lift(content))
        .onConflictUpdate(_.id)((t, e) => t.content -> e.content)
    }).transact(xa).map(_ > 0)
  }

  override def batchGet(ids: Seq[ID]): fs2.Stream[IO, ArticleContent] = {
    val q = quote {
      query[ArticleContent].filter(content => lift(ids).contains(content.id))
    }
    stream(q).transact(xa)
  }
}
