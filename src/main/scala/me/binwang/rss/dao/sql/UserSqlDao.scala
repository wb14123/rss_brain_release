package me.binwang.rss.dao.sql

import cats.effect.IO
import doobie.Fragment
import doobie.implicits._
import io.getquill.{idiom => _}
import me.binwang.rss.dao.UserDao
import me.binwang.rss.model.{ID, User, UserUpdater}

class UserSqlDao(implicit val connectionPool: ConnectionPool) extends UserDao with BaseSqlDao {
  import dbCtx._

  override def table: String = "rss_user"
  private implicit val userSchema: dbCtx.SchemaMeta[User] = schemaMeta[User]("rss_user")

  override def createTable(): IO[Unit] = {

    Fragment.const(s"""create table if not exists "$table" (
            id char($UUID_LENGTH) primary key,
            email varchar not null unique,
            username varchar not null,
            password varchar not null,
            salt varchar not null,
            defaultFolderID char($UUID_LENGTH) not null,
            lastLoginTime timestamp default null,
            createdAt timestamp default null,
            lastLoginIP varchar($IP_MAX_LENGTH) default null,
            description varchar default null,
            isActive boolean not null,
            isAdmin boolean not null,
            currentFolderID char($UUID_LENGTH) default null,
            currentSourceID char(${ID.maxLength}) default null,
            activeCode char($UUID_LENGTH) unique default null,
            subscribeEndAt timestamp not null,
            subscribed boolean not null default false,
            nsfwSetting varchar not null default 'BLUR',
            searchEngine jsonb not null,
            llmEngine varchar default null,
            llmApiKey varchar default null
          )
         """)
      .update
      .run
      .flatMap(_ => createIndex("email"))
      .flatMap(_ => createIndex("username"))
      .flatMap(_ => createIndex("activeCode"))
      .transact(xa)
      .map(_ => ())
  }

  override def getByID(userID: String): IO[Option[User]] = {
    val q = quote {
      query[User].filter(_.id == lift(userID)).take(1)
    }
    stream(q).transact(xa).compile.last
  }

  override def getByEmail(email: String): IO[Option[User]] = {
    val q = quote {
      query[User].filter(_.email == lift(email)).take(1)
    }
    stream(q).transact(xa).compile.last
  }

  override def getByActiveCode(activeCode: String): IO[Option[User]] = {
    val q = quote {
      query[User].filter(_.activeCode.contains(lift(activeCode))).take(1)
    }
    stream(q).transact(xa).compile.last
  }

  override def insertIfNotExists(user: User): IO[Boolean] = {
    val q = quote {
      query[User].insertValue(lift(user)).onConflictIgnore(_.email)
    }
    run(q).transact(xa).map(_ > 0)
  }

  override def update(userID: String, updater: UserUpdater): IO[Boolean] = {

    val q = dynamicQuerySchema[User](table)
      .filter(_.id == lift(userID))
      .update(
        setOpt(_.password, updater.password),
        setOpt(_.salt, updater.salt),
        setOpt(_.email, updater.email),
        setOpt(_.lastLoginTime, updater.lastLoginTime),
        setOpt(_.lastLoginIP, updater.lastLoginIP),
        setOpt(_.isActive, updater.isActive),
        setOpt(_.isAdmin, updater.isAdmin),
        setOpt(_.currentFolderID, updater.currentFolderID),
        setOpt(_.currentSourceID, updater.currentSourceID),
        setOpt(_.activeCode, updater.activeCode),
        setOpt(_.subscribeEndAt, updater.subscribeEndAt),
        setOpt(_.subscribed, updater.subscribed),
        setOpt(_.username, updater.username),
        setOpt(_.nsfwSetting, updater.nsfwSetting),
        setOpt(_.searchEngine, updater.searchEngine),
        setOpt(_.llmEngine, updater.llmEngine),
        setOpt(_.llmApiKey, updater.llmApiKey),
      )
    run(q).transact(xa).map(_ > 0)
  }

  override def delete(userID: String): IO[Boolean] =  {
    val q = quote {
      query[User].filter(_.id == lift(userID)).delete
    }
    run(q).transact(xa).map(_ > 0)
  }
}
