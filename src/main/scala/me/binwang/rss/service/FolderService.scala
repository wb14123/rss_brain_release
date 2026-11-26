package me.binwang.rss.service

import cats.effect._
import cats.implicits._
import me.binwang.archmage.core.CatsMacros.timed
import me.binwang.rss.dao.{FolderDao, FolderSourceDao, SourceDao}
import me.binwang.rss.metric.TimeMetrics
import me.binwang.rss.model._
import me.binwang.rss.parser.OPMLParser
import me.binwang.rss.parser.OPMLParser.{FolderWithSources, ImportSource}
import org.typelevel.log4cats.LoggerFactory

import java.io.InputStream
import java.time.{ZoneId, ZonedDateTime}
import java.util.UUID

/**
 * Folder related APIs.
 */
class FolderService (
    private val folderDao: FolderDao,
    private val folderSourceDao: FolderSourceDao,
    private val sourceDao: SourceDao,
    implicit val authorizer: Authorizer,
    private val importLimit: ImportLimit = ImportLimit(),
)(implicit val loggerFactory: LoggerFactory[IO]) extends TimeMetrics {

  private val logger = LoggerFactory.getLoggerFromClass[IO](this.getClass)

  /**
   * Import sources from an OPML file. Each source is subscribed to a folder, and the result tells how many of them
   * are subscribed after the import.
   *
   * @param token User token.
   * @param inputStream The OPML file.
   * @return the result of the import.
   */
  def importFromOPML(token: String, inputStream: Resource[IO, InputStream]): IO[ImportOPMLResult] = timed {
    authorizer.authorize(token).flatMap { session =>
      val userID = session.userID
      Clock[IO].realTimeInstant.flatMap { nowInstant =>
        val now = ZonedDateTime.ofInstant(nowInstant, ZoneId.systemDefault())
        OPMLParser.parse(inputStream, userID, now).flatMap { case (foldersWithSources, sourcesWithoutFolder) =>
          val allSources = (foldersWithSources.flatMap(_.sources) ++ sourcesWithoutFolder).distinctBy(_.source.id)
          checkImportLimit(session, allSources.size, foldersWithSources.size) >>
            importSources(foldersWithSources, sourcesWithoutFolder, userID)
              .flatMap(results => importResult(allSources, results, userID))
        }
      }
    }
  }

  /**
   * Export all the subscribed sources to an OPML file.
   */
  def exportOPML(token: String): IO[String] = timed {
    for {
      session <- authorizer.authorize(token)
      folderSources <- folderSourceDao.getSourcesByUser(session.userID).compile.toList
      folders <- folderDao.listByUser(session.userID).compile.toList
    } yield {
      val defaultFolderID = folders.find(_.isUserDefault).map(_.id).getOrElse("")
      val sourcesByFolder = folderSources
        .map{ folderSource =>
          (folderSource.folderMapping.folderID, ImportSource(folderSource.source, folderSource.displayName))
        }
        .groupBy(_._1)
        .view.mapValues(_.map(_._2)).toMap
      val foldersWithSources = folders.filter(!_.isUserDefault).map { folder =>
        FolderWithSources(
          folder = folder,
          sources = sourcesByFolder.getOrElse(folder.id, Seq())
        )
      }
      OPMLParser.generateXml(foldersWithSources, sourcesByFolder.getOrElse(defaultFolderID, Seq())).toString()
    }
  }

  /**
   * Get all the folders for a user.
   *
   * @param size How many folders to return at most. Default is 0. Must specify otherwise it will just return an empty response.
   * @param startPosition Only return folders that with a position lager than this.
   */
  def getMyFolders(token: String, size: Int, startPosition: Long): fs2.Stream[IO, Folder] = timed {
    authorizer.authorizeReadAsStream(token).flatMap { session =>
      folderDao.listByUser(session.userID, size, startPosition)
    }
  }

  /**
   * Get folder details by folder ID.
   */
  def getFolderByID(token: String, folderID: String):IO[Folder] = timed {
    authorizer.checkFolderReadPermission(token, folderID).map(_._2)
  }

  /**
   * Cleanup folder positions for a user. It will keep the folders with the same order, but make the positions start
   * from 1000 and the gap to be 1000.
   */
  def cleanupPosition(token: String): IO[Int] = timed {
    authorizer.authorize(token).flatMap { session =>
      folderDao.cleanupPositionForUser(session.userID)
    }
  }

  /**
   * Create a folder for the user.
   */
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

  /**
   * Update folder details. It will ignore `recommend` and `language` field.
   */
  def updateFolder(token: String, folderID: String, folderUpdater: FolderUpdater): IO[Folder] = timed {
    authorizer.checkFolderPermission(token, folderID).flatMap { case (session, _) =>
      val updater = if (session.isAdmin) folderUpdater else folderUpdater.copy(recommend = None, language = None)
      folderDao.update(folderID, updater).flatMap(_ => folderDao.getByID(folderID).map(_.get))
    }
  }

  /**
   * Get all the recommended folders. The client can display them as example feeds.
   */
  def getRecommendFolders(token: String, lang: String, size: Int, startPosition: Int): fs2.Stream[IO, Folder] = timed {
    authorizer.authorizeAsStream(token).flatMap { _ =>
      folderDao.listRecommended(lang, size, startPosition)
    }
  }

  /**
   * Delete a folder. The data cannot be recovered.
   */
  def deleteFolder(token: String, folderID: String): IO[Unit] = timed {
    authorizer.checkFolderPermission(token, folderID).flatMap { _ =>
      for {
        _ <- folderDao.delete(folderID)
        _ <- folderSourceDao.delAllInFolder(folderID)
      } yield None
    }
  }

  private case class SourceImportResult(
    importSource: ImportSource,
    error: Option[String],
  )

  private val writeImportError = "This feed could not be added because of a storage error. " +
    "Import the same file again to retry; feeds that were already added will not be duplicated."

  private def folderImportError(folderName: String): String = {
    s"""The folder "$folderName" could not be created or found, so this feed was not added. """ +
      "Import the same file again to retry."
  }

  private def checkImportLimit(session: UserSession, sourceSize: Int, folderSize: Int): IO[Unit] = {
    if (session.subscribed && sourceSize > importLimit.paidSourceCount.getOrElse(Int.MaxValue)) {
      IO.raiseError(PaidUserSourceLimitExceed(sourceSize, importLimit.paidSourceCount.get))
    } else if (session.subscribed && folderSize > importLimit.paidFolderCount.getOrElse(Int.MaxValue)) {
      IO.raiseError(PaidUserFolderLimitExceed(folderSize, importLimit.paidFolderCount.get))
    } else if (!session.subscribed && sourceSize > importLimit.freeSourceCount.getOrElse(Int.MaxValue)) {
      IO.raiseError(FreeTrailSourceLimitExceed(sourceSize, importLimit.freeSourceCount.get))
    } else if (!session.subscribed && folderSize > importLimit.freeFolderCount.getOrElse(Int.MaxValue)) {
      IO.raiseError(FreeTrailFolderLimitExceed(folderSize, importLimit.freeFolderCount.get))
    } else {
      IO.unit
    }
  }

  private def importSources(foldersWithSources: Seq[FolderWithSources], sourcesWithoutFolder: Seq[ImportSource],
      userID: String): IO[Seq[SourceImportResult]] = {
    for {
      defaultFolder <- if (sourcesWithoutFolder.isEmpty) IO.pure(Option.empty[Folder])
        else getUserDefaultFolder(userID).map(Some(_))
      resultsInFolders <- foldersWithSources.map(importFolder(_, userID)).sequence
      resultsInDefaultFolder <- defaultFolder match {
        case Some(folder) => addSourcesToFolder(sourcesWithoutFolder, folder, userID)
        case None => IO.pure(Seq())
      }
    } yield resultsInFolders.flatten ++ resultsInDefaultFolder
  }

  /**
   * A source is reported as failed only when no mapping was written for it and the user has no mapping for it yet.
   */
  private def importResult(allSources: Seq[ImportSource], results: Seq[SourceImportResult],
      userID: String): IO[ImportOPMLResult] = {
    val resultsBySourceID = results.groupBy(_.importSource.source.id)
    val failedCandidates = allSources.flatMap { importSource =>
      val sourceResults = resultsBySourceID.getOrElse(importSource.source.id, Seq())
      if (sourceResults.exists(_.error.isEmpty)) {
        None
      } else {
        sourceResults.flatMap(_.error).headOption.map { error =>
          (importSource.source.id, ImportFailedSource(importSource.source.xmlUrl, error))
        }
      }
    }
    failedCandidates
      .filterA { case (sourceID, _) => hasMapping(userID, sourceID).map(exists => !exists) }
      .map { failures =>
        val failedSources = failures.map(_._2)
        ImportOPMLResult(
          totalSources = allSources.size,
          successfulSources = allSources.size - failedSources.size,
          failedSources = failedSources,
        )
      }
  }

  private def hasMapping(userID: String, sourceID: String): IO[Boolean] = {
    folderSourceDao.getSourcesByID(userID, sourceID, 1).compile.toList
      .map(_.nonEmpty)
      .handleErrorWith { err =>
        logger.error(err)(s"Error to check existing mapping of source $sourceID for user $userID").map(_ => false)
      }
  }

  private def getUserDefaultFolder(userID: String): IO[Folder] = {
    folderDao.getUserDefaultFolder(userID).flatMap {
      case Some(folder) => IO.pure(folder)
      case None => IO.raiseError(new Exception(s"User $userID doesn't have default folder"))
    }
  }

  /**
   * Import the sources of one folder group. A folder that cannot be created or found fails all of its sources, but
   * doesn't stop the import of the other groups.
   */
  private def importFolder(folderWithSources: FolderWithSources, userID: String): IO[Seq[SourceImportResult]] = {
    val folder = folderWithSources.folder
    resolveFolder(folder, userID).attempt.flatMap {
      case Right(resolvedFolder) => addSourcesToFolder(folderWithSources.sources, resolvedFolder, userID)
      case Left(err) =>
        val error = folderImportError(folder.name)
        val xmlUrls = folderWithSources.sources.map(_.source.xmlUrl).mkString(", ")
        logger.error(err)(s"Error to add folder ${folder.name} for user $userID, feeds: $xmlUrls") >>
          IO.pure(folderWithSources.sources.map(SourceImportResult(_, Some(error))))
    }
  }

  /**
   * @return The folder to put the sources in. It might be an existing folder with the same name.
   */
  private def resolveFolder(folder: Folder, userID: String): IO[Folder] = {
    folderDao.insertIfNotExist(folder).flatMap {
      case true => IO.pure(folder)
      case false => folderDao.getByUserAndName(userID, folder.name).flatMap {
        case Some(existingFolder) => IO.pure(existingFolder)
        case None => IO.raiseError(FolderNotFound(folder.name))
      }
    }
  }

  private def addSourcesToFolder(sources: Seq[ImportSource], folder: Folder, userID: String):
      IO[Seq[SourceImportResult]] = {
    sources.zipWithIndex.map { case (importSource, index) =>
      val source = importSource.source
      val add = sourceDao.insert(source) >>
        folderSourceDao.addSourceToFolder(FolderSourceMapping(
          folder.id, source.id, userID, index * 1000, importedSourceName = importSource.name)).void
      add.attempt.flatMap {
        case Right(_) => IO.pure(SourceImportResult(importSource, None))
        case Left(err) => logger.error(err)(
          s"Error to add source ${source.xmlUrl} to folder ${folder.name} for user $userID") >>
          IO.pure(SourceImportResult(importSource, Some(writeImportError)))
      }
    }.sequence
  }

}
