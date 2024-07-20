package me.binwang.rss.service

import cats.effect.IO
import cats.effect.kernel.Clock
import me.binwang.rss.dao.{FolderDao, UserSessionDao}
import me.binwang.rss.model._
import me.binwang.rss.util.Throttler
import org.typelevel.log4cats.LoggerFactory

import java.time.{ZoneId, ZonedDateTime}

class Authorizer(
  private val throttler: Throttler,
  private implicit val userSessionDao: UserSessionDao,
  private implicit val folderDao: FolderDao,
)(implicit val loggerFactory: LoggerFactory[IO]) {

  private val logger = LoggerFactory.getLoggerFromClass[IO](this.getClass)

  def authorize(token: String, allowNotPaid: Boolean = false): IO[UserSession] = {
    userSessionDao.get(token).flatMap {
      case Some(session) =>
        throttler.throttleUser(session.userID)
        Clock[IO].realTimeInstant.flatMap { nowInstant =>
          val now = ZonedDateTime.ofInstant(nowInstant, ZoneId.systemDefault())
          if (!allowNotPaid && session.subscribeEndTime.isBefore(now)) {
            IO.raiseError(UserSubscriptionEnded(session.userID, session.subscribeEndTime))
          } else {
            logger.info(s"Access authorized, token: $token, user: ${session.userID}").map(_ => session)
          }
        }
      case None =>
        logger.info(s"Access denied, token: $token") >>
          IO.raiseError(UserNotAuthorized(token))
    }
  }

  def authorizeAsStream(token: String, allowNotePaid: Boolean = false): fs2.Stream[IO, UserSession] = {
    fs2.Stream.eval(authorize(token, allowNotePaid)).last.map(_.get)
  }

  def authorizeAdmin(token: String): IO[UserSession] = {
    authorize(token).map { session =>
      if (!session.isAdmin) {
        throw UserIsNotAdmin(session.userID)
      }
      session
    }
  }

  def checkFolderPermission(token: String, folderID: String): IO[(UserSession, Folder)] = {
    authorize(token).flatMap { session =>
      folderDao.getByID(folderID).map {
        case Some(folder) if folder.userID.equals(session.userID) => (session, folder)
        case _ => throw NoPermissionOnFolder(session.userID, folderID)
      }
    }
  }

  def checkFoldersPermission(token: String, folderIDs: Seq[String]): IO[UserSession] = {
    authorize(token).flatMap { session =>
      folderDao.listByIDs(folderIDs).compile.toList.map{ folderList =>
        if (folderList.length != folderIDs.length) {
          throw NoPermissionOnFolders(session.userID, folderIDs)
        }
        if (folderList.exists(!_.userID.equals(session.userID))) {
          throw NoPermissionOnFolders(session.userID, folderIDs)
        } else {
          session
        }
      }
    }
  }

  def checkFolderPermissionAsStream(token: String, folderID: String): fs2.Stream[IO, UserSession] = {
    fs2.Stream.eval(checkFolderPermission(token, folderID)).last.map(_.get._1)
  }

  def checkFolderReadPermission(token: String, folderID: String): IO[UserSession] = {
    authorize(token).flatMap { session =>
      folderDao.getByID(folderID).map {
        case Some(folder) if folder.userID.equals(session.userID) || folder.recommend => session
        case _ => throw NoPermissionOnFolder(session.userID, folderID)
      }
    }
  }

  def checkFolderReadPermissionAsStream(token: String, folderID: String): fs2.Stream[IO, UserSession] = {
    fs2.Stream.eval(checkFolderReadPermission(token, folderID)).last.map(_.get)
  }

}
