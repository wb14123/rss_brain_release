package me.binwang.rss.service

import cats.effect._
import cats.implicits._
import me.binwang.archmage.core.CatsMacros.timed
import me.binwang.rss.dao.{FolderDao, FolderSourceDao, SourceDao}
import me.binwang.rss.metric.TimeMetrics
import me.binwang.rss.model._
import me.binwang.rss.parser.OPMLParser
import me.binwang.rss.parser.OPMLParser.FolderWithSources
import org.typelevel.log4cats.LoggerFactory

import java.io.InputStream
import java.time.{ZoneId, ZonedDateTime}
import java.util.UUID

class FolderService (
    private val folderDao: FolderDao,
    private val folderSourceDao: FolderSourceDao,
    private val sourceDao: SourceDao,
    implicit val authorizer: Authorizer,
)(implicit val loggerFactory: LoggerFactory[IO]) extends TimeMetrics {

  private val logger = LoggerFactory.getLoggerFromClass[IO](this.getClass)

  /**
   * Import folder and sources from OPML file
   * @param token User token
   * @param inputStream Input stream for the OPML file
   * @return Number of failed imported sources
   */
  def importFromOPML(token: String, inputStream: Resource[IO, InputStream]): IO[Int] = timed {
    authorizer.authorize(token).flatMap { session =>
      val userID = session.userID
      Clock[IO].realTimeInstant.flatMap { nowInstant =>
        val now = ZonedDateTime.ofInstant(nowInstant, ZoneId.systemDefault())
        OPMLParser.parse(inputStream, session.userID, now).flatMap { case (foldersWithSources, sourcesWithoutFolder) =>
          val importSources = folderDao
            .getUserDefaultFolder(userID)
            .flatMap {
              case None => IO.raiseError(new Exception(s"User $userID doesn't have default folder"))
              case Some(folder) => IO.pure(Some(folder))
            }
            .handleErrorWith { err =>
              logger.error(err)(s"Error to find default folder for user $userID").map(_ => None)
            }
            .flatMap {
              case Some(folder) => addSourcesToFolder(sourcesWithoutFolder, folder.id, userID)
              case None => IO.pure(sourcesWithoutFolder.length)
            }
          val importFolders = foldersWithSources.map(importFolderWithSources(_, userID)).toList.parSequence.map(_.sum)
          (importSources, importFolders).parMapN(
            (failedSources, failedFolderSources) => failedSources + failedFolderSources)
        }
      }
    }
  }

  def exportOPML(token: String): IO[String] = timed {
    for {
      session <- authorizer.authorize(token)
      folderSources <- folderSourceDao.getSourcesByUser(session.userID).compile.toList
      folders <- folderDao.listByUser(session.userID).compile.toList
    } yield {
      val defaultFolderID = folders.find(_.isUserDefault).map(_.id).getOrElse("")
      val folderSourceMap = folderSources
        .map{ folderSource => (folderSource.folderMapping.folderID, folderSource.source)}
        .groupBy(_._1)
        .transform((_, v) => v.map(_._2))
      val foldersWithSources = folders.filter(!_.isUserDefault).map { folder =>
        FolderWithSources(
          folder = folder,
          sources = folderSourceMap.getOrElse(folder.id, Seq())
        )
      }
      OPMLParser.generateXml(foldersWithSources, folderSourceMap.getOrElse(defaultFolderID, Seq())).toString()
    }
  }

  def getMyFolders(token: String, size: Int, startPosition: Long): fs2.Stream[IO, Folder] = timed {
    authorizer.authorizeAsStream(token).flatMap { session =>
      folderDao.listByUser(session.userID, size, startPosition)
    }
  }

  def getFolderByID(token: String, folderID: String):IO[Folder] = timed {
    authorizer.checkFolderPermission(token, folderID).map(_._2)
  }

  def cleanupPosition(token: String): IO[Int] = timed {
    authorizer.authorize(token).flatMap { session =>
      folderDao.cleanupPositionForUser(session.userID)
    }
  }

  def addFolder(token: String, folder: FolderCreator): IO[Folder] = timed {
    authorizer.authorize(token).flatMap { session =>
      val insertFolder = Folder(
        id = UUID.randomUUID().toString,
        userID = session.userID,
        name = folder.name,
        description = folder.description,
        position = folder.position,
        count = 0 // will increase while add sources to folder
      )
      folderDao.insertIfNotExist(insertFolder).flatMap {
        case true => IO.pure(insertFolder)
        case false => IO.raiseError(FolderDuplicateError(insertFolder.id, folder.name))
      }
    }
  }

  def updateFolder(token: String, folderID: String, folderUpdater: FolderUpdater): IO[Folder] = timed {
    authorizer.checkFolderPermission(token, folderID).flatMap { case (session, _) =>
      val updater = if (session.isAdmin) folderUpdater else folderUpdater.copy(recommend = None, language = None)
      folderDao.update(folderID, updater).flatMap(_ => folderDao.getByID(folderID).map(_.get))
    }
  }

  def getRecommendFolders(token: String, lang: String, size: Int, startPosition: Int): fs2.Stream[IO, Folder] = timed {
    authorizer.authorizeAsStream(token).flatMap { _ =>
      folderDao.listRecommended(lang, size, startPosition)
    }
  }

  def deleteFolder(token: String, folderID: String): IO[Unit] = timed {
    authorizer.checkFolderPermission(token, folderID).flatMap { _ =>
      for {
        _ <- folderDao.delete(folderID)
        _ <- folderSourceDao.delAllInFolder(folderID)
      } yield None
    }
  }

  private def importFolderWithSources(folderWithSources: FolderWithSources, userID: String): IO[Int] = {
    val folder = folderWithSources.folder
    folderDao
      .insertIfNotExist(folder)
      .flatMap {
        case true => IO.pure(Some(folder.id))
        case false => folderDao.getByUserAndName(userID, folder.name).map(_.map(_.id))
      }
      .handleErrorWith { err =>
        logger.error(err)(s"Error to add folder ${folder.name} for user $userID").map(_ => None)
      }
      .flatMap {
        case Some(folderID) => addSourcesToFolder(folderWithSources.sources, folderID, userID)
        case None => IO.pure(folderWithSources.sources.length)
      }
  }

  private def addSourcesToFolder(sources: Seq[Source], folderID: String, userID: String): IO[Int] = {
    sources.zipWithIndex.map { case (source, idx) =>
      val task = for {
        _ <- sourceDao.insert(source)
        _ <- folderSourceDao.addSourceToFolder(
          FolderSourceMapping(folderID, source.id, userID, idx * 1000, source.title))
      } yield None
      task.handleErrorWith{ err =>
        logger.error(err)(s"Error to add source ${source.xmlUrl} for user $userID").map(_ => Some(source.xmlUrl))
      }
    }
      .toList
      .parSequence
      .map(_.count(_.isDefined))
  }


}
