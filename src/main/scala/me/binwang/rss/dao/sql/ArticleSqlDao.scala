package me.binwang.rss.dao.sql

import cats.effect.IO
import doobie._
import doobie.implicits._
import io.getquill.Ord
import me.binwang.rss.dao.ArticleDao
import me.binwang.rss.model.ID.ID
import me.binwang.rss.model.{Article, ArticleUserMarking, ArticleWithUserMarking, FolderSourceMapping, ID}

import java.time.ZonedDateTime

class ArticleSqlDao(implicit val connectionPool: ConnectionPool) extends ArticleDao with BaseSqlDao {

  override def table = "article"

  import dbCtx._

  override def createTable(): IO[Unit] = {
    Fragment.const(s"""
      create table if not exists $table (
        id char(${ID.maxLength}) primary key,
        title varchar not null,
        sourceID char(${ID.maxLength}) not null,
        sourceTitle varchar default null,
        guid varchar not null,
        link varchar not null,
        createdAt timestamp not null,
        postedAt timestamp not null,
        description varchar not null,
        author varchar default null,
        comments int default null,
        upVotes int default null,
        downVotes int default null,
        score double precision not null default 0,
        mediaGroups jsonb default null,
        nsfw boolean default false,
        postedAtIsMissing boolean not null default false
      )""")
      .update
      .run
      .flatMap(_ => createIndex("sourceID"))
      // optimize for Cockroachdb: this forces it to filter on sourceID first then on postedAt
      .flatMap(_ => createIndex("createdAt", desc = true))
      .flatMap(_ => createIndexWithFields(Seq(("sourceID", false), ("postedAt", true))))
      // .flatMap(_ => createIndex("score", desc = true))
      .flatMap(_ => createIndexWithFields(Seq(("sourceID", false), ("score", true))))
      .map(_ => ())
      .transact(xa)
  }

  override def get(id: ID): IO[Option[Article]] = {
    stream(quote{
      query[Article]
        .filter(_.id == lift(id))
        .take(1)
    }).transact(xa).compile.last
  }


  override def listBySource(sourceID: ID, size: Int, postedBefore: ZonedDateTime, articleID: ID): fs2.Stream[IO, Article] = {
    stream(quote{
      query[Article]
        .filter(_.sourceID == lift(sourceID))
        .filter(article => article.postedAt < lift(postedBefore) ||
          (article.postedAt == lift(postedBefore) && (article.id greaterThan lift(articleID))))
        .sortBy(article => (article.postedAt, article.id))(Ord(Ord.desc, Ord.asc))
        .take(lift(size))
    }).transact(xa)
  }

  override def listBySourceWithUserMarking(sourceID: ID, size: Int, postedBefore: ZonedDateTime, articleID: ID,
      userID: String, read: Option[Boolean], bookmarked: Option[Boolean],
      deleted: Option[Boolean] = Some(false)): fs2.Stream[IO, ArticleWithUserMarking] = {
    val baseQ = quote{
      query[Article]
        .leftJoin(query[ArticleUserMarking])
        .on(_.id == _.articleID)
        .filter({case (_, markingOpt) => markingOpt.isEmpty || markingOpt.exists(_.userID == lift(userID))})
        .filter(_._1.sourceID == lift(sourceID))
        .filter({case (article, _) => article.postedAt < lift(postedBefore) ||
          (article.postedAt == lift(postedBefore) && (article.id greaterThan lift(articleID)))})
        .sortBy(entry => (entry._1.postedAt, entry._1.id))(Ord(Ord.desc, Ord.asc))
    }.dynamic
    val q = filterArticlesWithMarking(baseQ, read, bookmarked, deleted).take(size)
    stream(q).transact(xa).map{case (article, markingOpt) =>
      ArticleWithUserMarking(article, markingOpt.getOrElse(ArticleUserMarking(article.id, userID)))}
  }

  /**
   * Insert article if not exists, update some fields otherwise.
   *
   * Check exist by sourceID and guid.
   *
   * @param article The article to be inserted
   * @return Article ID if it is already exist. None otherwise
   */
  override def insertOrUpdate(article: Article): IO[Boolean] = {
    run(quote {
      query[Article]
        .insertValue(lift(article))
        .onConflictUpdate(_.id)(
          (t, e) => t.title -> e.title,
          (t, e) => t.sourceID -> e.sourceID, // update source info in case of hash conflict
          (t, e) => t.sourceTitle -> e.sourceTitle,
          (t, e) => t.guid -> e.guid,
          (t, e) => t.description -> e.description,
          (t, e) => t.link -> e.link,
          (t, e) => t.postedAt -> e.postedAt,
          (t, e) => t.upVotes -> e.upVotes,
          (t, e) => t.downVotes -> e.downVotes,
          (t, e) => t.comments -> e.comments,
          (t, e) => t.score -> e.score,
          (t, e) => t.author -> e.author,
          (t, e) => t.mediaGroups -> e.mediaGroups,
          (t, e) => t.nsfw -> e.nsfw,
          (t, e) => t.postedAtIsMissing -> e.postedAtIsMissing,
        )
    }).transact(xa).map(_ > 0)
  }

  override def listByFolder(folderID: String, size: Int, postedBefore: ZonedDateTime, articleID: ID): fs2.Stream[IO, Article] = {
    val q = quote {
      query[Article]
        .join(query[FolderSourceMapping])
        .on(_.sourceID == _.sourceID)
        .filter(_._2.folderID == lift(folderID))
        .map(_._1)
        .filter(article => article.postedAt < lift(postedBefore) ||
          (article.postedAt == lift(postedBefore) && (article.id greaterThan lift(articleID))))
        .sortBy(article => (article.postedAt, article.id))(Ord(Ord.desc, Ord.asc))
        .take(lift(size))
    }
    stream(q).transact(xa)
  }

  override def listByFolderWithUserMarking(folderID: String, size: Int, postedBefore: ZonedDateTime, articleID: ID,
      userID: String, read: Option[Boolean], bookmarked: Option[Boolean],
      deleted: Option[Boolean] = Some(false)): fs2.Stream[IO, ArticleWithUserMarking] = {
    val baseQ = quote {
      query[Article]
        .join(query[FolderSourceMapping])
        .on(_.sourceID == _.sourceID)
        .leftJoin(query[ArticleUserMarking])
        .on(_._1.id == _.articleID)
        .filter(_._1._2.folderID == lift(folderID))
        .map(x => (x._1._1, x._2))
        .filter({case (_, markingOpt) => markingOpt.isEmpty || markingOpt.exists(_.userID == lift(userID))})
        .filter({case (article, _) => article.postedAt < lift(postedBefore) ||
          (article.postedAt == lift(postedBefore) && (article.id greaterThan lift(articleID)))})
        .sortBy({case (article, _) => (article.postedAt, article.id)})(Ord(Ord.desc, Ord.asc))
    }.dynamic
    val q = filterArticlesWithMarking(baseQ, read, bookmarked, deleted).take(size)

    stream(q).transact(xa).map{case (article, markingOpt) =>
      ArticleWithUserMarking(article, markingOpt.getOrElse(ArticleUserMarking(article.id, userID)))}
  }

  override def listByUser(userID: String, size: Int, postedBefore: ZonedDateTime, articleID: ID): fs2.Stream[IO, Article] =  {
    val q = quote {
      query[FolderSourceMapping]
        .filter(_.userID == lift(userID))
        .map(_.sourceID)
        .distinct
        .join(query[Article])
        .on { case (sourceID, article) => sourceID == article.sourceID }
        .map(_._2)
        .filter { article =>
          article.postedAt < lift(postedBefore) ||
            (article.postedAt == lift(postedBefore) && (article.id greaterThan lift(articleID)))
        }
        .sortBy(article => (article.postedAt, article.id))(Ord(Ord.desc, Ord.asc))
        .take(lift(size))
    }
    stream(q).transact(xa)
  }

  override def listByUserWithUserMarking(userID: String, size: Int, postedBefore: ZonedDateTime, articleID: ID,
      read: Option[Boolean], bookmarked: Option[Boolean],
      deleted: Option[Boolean] = Some(false)): fs2.Stream[IO, ArticleWithUserMarking] =  {
    val baseQ = quote {
      query[FolderSourceMapping]
        .filter(_.userID == lift(userID))
        .map(_.sourceID)
        .distinct
        .join(query[Article])
        .on{case (sourceID, article) => sourceID == article.sourceID}
        .map(_._2)
        .leftJoin(query[ArticleUserMarking])
        .on{case (article, marking) => article.id == marking.articleID && marking.userID == lift(userID)}
        .filter { case (article, _) => article.postedAt < lift(postedBefore) ||
          (article.postedAt == lift(postedBefore) && (article.id greaterThan lift(articleID)))
        }
        .sortBy({case (article, _)=> (article.postedAt, article.id)})(Ord(Ord.desc, Ord.asc))
    }.dynamic
    val q = filterArticlesWithMarking(baseQ, read, bookmarked, deleted).take(size)
    stream(q).transact(xa).map{case (article, markingOpt) =>
      ArticleWithUserMarking(article, markingOpt.getOrElse(ArticleUserMarking(article.id, userID)))}
  }

  override def listBySourceOrderByScoreWithUserMarking(sourceID: ID, size: Index, maxScore: Double, articleID: ID,
      userID: String, read: Option[Boolean], bookmarked: Option[Boolean],
      deleted: Option[Boolean] = Some(false)): fs2.Stream[IO, ArticleWithUserMarking] = {
    val baseQ = quote{
      query[Article]
        .leftJoin(query[ArticleUserMarking])
        .on{case (article, marking) => article.id == marking.articleID && marking.userID == lift(userID)}
        .filter(_._1.sourceID == lift(sourceID))
        .filter({case (article, _) => article.score <= lift(maxScore) &&
          !(article.score == lift(maxScore) && (article.id lessEqThan lift(articleID)))})
        .sortBy(entry => (entry._1.score, entry._1.id))(Ord(Ord.desc, Ord.asc))
    }.dynamic
    val q = filterArticlesWithMarking(baseQ, read, bookmarked, deleted).take(size)
    stream(q).transact(xa).map{case (article, markingOpt) =>
      ArticleWithUserMarking(article, markingOpt.getOrElse(ArticleUserMarking(article.id, userID)))}
  }

  override def listByFolderOrderByScoreWithUserMarking(folderID: String, size: Index, maxScore: Double, articleID: ID,
      userID: String, read: Option[Boolean], bookmarked: Option[Boolean],
      deleted: Option[Boolean] = Some(false)): fs2.Stream[IO, ArticleWithUserMarking] = {
    val baseQ = quote {
      query[Article]
        .join(query[FolderSourceMapping])
        .on(_.sourceID == _.sourceID)
        .leftJoin(query[ArticleUserMarking])
        .on{case ((article, _), marking) => article.id == marking.articleID && marking.userID == lift(userID)}
        .filter(_._1._2.folderID == lift(folderID))
        .map(x => (x._1._1, x._2))
        .filter({case (article, _) => article.score <= lift(maxScore) &&
          !(article.score == lift(maxScore) && (article.id lessEqThan lift(articleID)))})
        .sortBy({case (article, _) => (article.score, article.id)})(Ord(Ord.desc, Ord.asc))
    }.dynamic
    val q = filterArticlesWithMarking(baseQ, read, bookmarked, deleted).take(size)
    stream(q).transact(xa).map{case (article, markingOpt) =>
      ArticleWithUserMarking(article, markingOpt.getOrElse(ArticleUserMarking(article.id, userID)))}
  }

  private def filterArticlesWithMarking(query: DynamicQuery[(Article, Option[ArticleUserMarking])], read: Option[Boolean],
      bookmarked: Option[Boolean], deleted: Option[Boolean]) = {
    var q = query
    if (read.isDefined) {
      if (read.get) {
        q = q.filter(x => quote(x._2.exists(_.read == true)))
      } else {
        q = q.filter(x => quote(x._2.isEmpty || x._2.exists(_.read == false)))
      }
    }
    if (bookmarked.isDefined) {
      if (bookmarked.get) {
        q = q.filter(x => quote(x._2.exists(_.bookmarked == true)))
      } else {
        q = q.filter(x => quote(x._2.isEmpty || x._2.exists(_.bookmarked == false)))
      }
    }
    if (deleted.isDefined) {
      if (deleted.get) {
        q = q.filter(x => quote(x._2.exists(_.deleted == true)))
      } else {
        q = q.filter(x => quote(x._2.isEmpty || x._2.exists(_.deleted == false)))
      }
    }
    q
  }
}
