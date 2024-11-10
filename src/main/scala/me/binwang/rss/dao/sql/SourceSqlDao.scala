package me.binwang.rss.dao.sql

import cats.effect.{Clock, IO}
import doobie._
import doobie.implicits._
import io.getquill.{Query, idiom => _, _}
import me.binwang.rss.dao.SourceDao
import me.binwang.rss.model.FetchStatus.FetchStatus
import me.binwang.rss.model.ID.ID
import me.binwang.rss.model._

import java.time.{ZoneId, ZonedDateTime}

class SourceSqlDao(implicit val connectionPool: ConnectionPool) extends SourceDao with BaseSqlDao {

  override def table: String = "source"

  import dbCtx._

  private implicit val userSchema: dbCtx.SchemaMeta[User] = schemaMeta[User]("rss_user")

  override def createTable(): IO[Unit] = {
    Fragment.const(s"""create table if not exists $table (
            id char(${ID.maxLength}) primary key,
            title varchar default null,
            xmlUrl varchar not null unique,
            htmlUrl varchar default null,
            description varchar default null,
            importedAt timestamp not null,
            updatedAt timestamp not null,
            fetchScheduledAt timestamp not null,
            fetchStartedAt timestamp default null,
            fetchCompletedAt timestamp default null,
            fetchStatus varchar not null,
            fetchFailedMsg varchar default null,
            fetchDelayMillis bigint not null,
            fetchErrorCount int not null default 0,
            articleOrder varchar not null,
            iconUrl varchar default null,
            showTitle bool not null default true,
            showFullArticle bool not null default false,
            showMedia bool not null default false
          )
         """)
      .update
      .run
      .flatMap(_ => createIndex("fetchScheduledAt"))
      .flatMap(_ => createIndex("fetchStatus"))
      .flatMap(_ => createIndexWithFields(Seq(("fetchStatus", false), ("fetchScheduledAt", false))))
      .transact(xa)
      .map(_ => ())
  }

  override def insert(source: Source): IO[Unit] = {
    run(quote {
      query[Source].insertValue(lift(source)).onConflictIgnore
    }).transact(xa).map(_ => ())
  }

  override def update(id: ID, updater: SourceUpdater): IO[Boolean] = {
    Clock[IO].realTimeInstant.flatMap { nowInstant =>
      val now = ZonedDateTime.ofInstant(nowInstant, ZoneId.systemDefault())
      val q = dynamicQuery[Source]
        .filter(_.id == lift(id))
        .update(
          setOpt(_.description, updater.description),
          setOpt(_.fetchCompletedAt, updater.fetchCompletedAt),
          setOpt(_.fetchStatus, updater.fetchStatus),
          setOpt(_.fetchDelayMillis, updater.fetchDelayMillis),
          setOpt(_.fetchFailedMsg, updater.fetchFailedMsg),
          setOpt(_.fetchScheduledAt, updater.fetchScheduledAt),
          setOpt(_.htmlUrl, updater.htmlUrl),
          setOpt(_.title, updater.title),
          setOpt(_.fetchStartedAt, updater.fetchStartedAt),
          setOpt(_.articleOrder, updater.articleOrder),
          setOpt(_.iconUrl, updater.iconUrl),
          setOpt(_.updatedAt, Some(now)),
          setOpt(_.fetchErrorCount, updater.fetchErrorCount),
          setOpt(_.showTitle, updater.showTitle),
          setOpt(_.showMedia, updater.showMedia),
          setOpt(_.showFullArticle, updater.showFullArticle),
        )
      run(q).transact(xa).map(_ > 0)
    }
  }

  override def get(id: ID): IO[Option[Source]] = {
    run(quote {
      query[Source].filter(_.id == lift(id)).take(1)
    })
      .transact(xa)
      .map(_.headOption)
  }

  /**
   * Get source urls need to be fetched. Which is source.fetchScheduledAt < now and source.fetchStatus = SCHEDULED.
   * The returned tasks will be sort by source.fetchScheduledAt.
   * It also set the returned source.fetchStatus to PENDING
   *
   * @param size How many sources to get
   * @return The sources need to be fetched
   */
  override def getFetchURLs(size: Int, currentTime: ZonedDateTime): fs2.Stream[IO, String] = {
    val sourcesQuery = quote {
      query[Source]
        .filter(source => source.fetchScheduledAt < lift(currentTime))
        .filter(_.fetchStatus == lift(FetchStatus.SCHEDULED))
        .sortBy(source => source.fetchScheduledAt) (Ord.asc)
        .take(lift(size))
        .forUpdate()
    }
    /*
    This doesn't work because it returns String instead of List[String]

    val updateQuery = quote {
      query[Source]
        .filter(source => sourcesQuery.contains(source.id))
        .update(_.fetchStatus -> FetchStatus.PENDING)
        .returning(_.xmlUrl)
    }
    run(updateQuery).transact(xa)
     */

    val q = for {
      sources <- run(sourcesQuery)
      _ <- run(
        query[Source]
          .filter(s => liftQuery(sources.map(_.id)).contains(s.id))
          .update(
            _.fetchStatus -> lift(FetchStatus.PENDING),
            _.fetchStartedAt -> lift(Option(currentTime)),
          )
      )
    } yield sources
    val urls = q.transact(xa).map(_.map(_.xmlUrl))
    fs2.Stream.eval(urls).flatMap(fs2.Stream.emits(_))
  }

  override def timeoutFetching(timeBefore: ZonedDateTime, currentTime: ZonedDateTime): IO[Int] = {
    val q = quote {
      query[Source]
        .filter(source => source.fetchStartedAt.exists(_ < lift(timeBefore)))
        .filter(source => source.fetchStatus == lift(FetchStatus.FETCHING)
          || source.fetchStatus == lift(FetchStatus.PENDING))
        .update(
          _.fetchCompletedAt -> lift(Option(currentTime)),
          _.fetchStatus -> lift(FetchStatus.SCHEDULED),
          _.fetchFailedMsg -> lift(Option("Time out")),
        )
    }
    run(q).transact(xa).map(_.toInt)
  }


  override def pauseNotInFolderSources(): IO[Long] = {
    val sourceQuery = quote {
      query[Source]
        .leftJoin(query[FolderSourceMapping])
        .on(_.id == _.sourceID)
        .filter(_._1.fetchStatus == lift(FetchStatus.SCHEDULED))
        .filter(_._2.map(_.sourceID).isEmpty)
        .map(_._1.id)
    }
    val q = setSourceFetchStatus(sourceQuery, FetchStatus.PAUSED)
    q.transact(xa)
  }

  /*
   TODO: this will actually pause more sources than needed. For example, if an deactivated user has a source that
   is also shared by other users, we shouldn't stop them but actually we do in this implementation.

   Currently we follow this by `resumeSourcesForActiveUsers` so that the sources shouldn't be paused will be resumed
   again. But we best fix the problem here.
   */
  override def pauseSourcesForDeactivatedUsers(now: ZonedDateTime): IO[Long] = {
    val sourceQuery = quote {
      query[Source]
        .join(query[FolderSourceMapping])
        .on(_.id == _.sourceID)
        .leftJoin(query[User])
        .on(_._2.userID == _.id)
        .filter(_._1._1.fetchStatus == lift(FetchStatus.SCHEDULED))
        .filter(r => r._2.isEmpty || r._2.exists(user => !user.isActive || user.subscribeEndAt < lift(now)))
        .map(_._1._1.id)
    }
    val q = setSourceFetchStatus(sourceQuery, FetchStatus.PAUSED)
    q.transact(xa)
  }

  override def resumeSourcesForActiveUsers(now: ZonedDateTime): IO[Long] = {
    val sourceQuery = quote {
      query[Source]
        .join(query[FolderSourceMapping])
        .on(_.id == _.sourceID)
        .join(query[User])
        .on(_._2.userID == _.id)
        .filter(_._1._1.fetchStatus == lift(FetchStatus.PAUSED))
        .filter { r =>
          val user = r._2
          user.isActive && user.subscribeEndAt > lift(now)
        }
        .map(_._1._1.id)
    }
    val q = setSourceFetchStatus(sourceQuery, FetchStatus.SCHEDULED)
    q.transact(xa)
  }


  override def resumeSourcesForUser(userID: ID): IO[Long] = {
    val sourceQuery = quote {
      query[Source]
        .join(query[FolderSourceMapping])
        .on(_.id == _.sourceID)
        .join(query[User])
        .on(_._2.userID == _.id)
        .filter(_._2.id == lift(userID))
        .filter(_._1._1.fetchStatus == lift(FetchStatus.PAUSED))
        .map(_._1._1.id)
    }
    val q = setSourceFetchStatus(sourceQuery, FetchStatus.SCHEDULED)
    q.transact(xa)
  }

  private def setSourceFetchStatus(sourceQuery: Quoted[Query[ID]], fetchStatus: FetchStatus) = {
    for {
      sources <- run(sourceQuery)
      updated <- run(
        query[Source]
          .filter(s => liftQuery(sources).contains(s.id))
          .update(s => s.fetchStatus -> lift(fetchStatus))
      )
    } yield updated
  }

}
