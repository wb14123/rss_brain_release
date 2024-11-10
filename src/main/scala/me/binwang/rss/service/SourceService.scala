package me.binwang.rss.service

import cats.effect.{Clock, IO}
import cats.implicits._
import me.binwang.archmage.core.CatsMacros.timed
import me.binwang.rss.dao.{FolderDao, FolderSourceDao, SourceDao}
import me.binwang.rss.fetch.fetcher.BackgroundFetcher
import me.binwang.rss.metric.TimeMetrics
import me.binwang.rss.model.ID.ID
import me.binwang.rss.model._
import me.binwang.rss.sourcefinder.{SourceFinder, SourceResult}
import me.binwang.rss.util.IOToStream.seqToStream
import org.typelevel.log4cats.LoggerFactory

import java.time.{ZoneId, ZonedDateTime}

class SourceService(
    private val sourceDao: SourceDao,
    private val folderSourceDao: FolderSourceDao,
    private val folderDao: FolderDao,
    private val fetcher: BackgroundFetcher,
    private val authorizer: Authorizer,
    private val sourceFinder: SourceFinder,
)(implicit val loggerFactory: LoggerFactory[IO]) extends TimeMetrics {

  private val logger = LoggerFactory.getLoggerFromClass[IO](this.getClass)

  // TODO: BUG: also need folder param for pagination
  def getMySourcesWithFolders(token: String, size: Int, startPosition: Long): fs2.Stream[IO, FolderSource] = timed {
    authorizer.authorizeAsStream(token).flatMap { session =>
      folderSourceDao.getSourcesByUser(session.userID, size, startPosition)
    }
  }

  def getSourceInFolder(token: String, folderID: String, sourceID: String): IO[FolderSource] = timed {
    authorizer.checkFolderReadPermission(token, folderID).flatMap { _ =>
      folderSourceDao.get(folderID, sourceID).map {
        case None => throw SourceInFolderNotFoundError(sourceID, folderID)
        case Some(x) => x
      }
    }
  }

  /*
  Get a folder source from user. There might be many, this just get a random one.
   */
  def getSourceInUser(token: String, sourceID: String): IO[FolderSource] = timed {
    authorizer.authorize(token).flatMap { session =>
      folderSourceDao.getSourcesByID(session.userID, sourceID, 1).compile.toList.map{
        case x if x.isEmpty => throw SourceNotFound(sourceID)
        case x => x.head
      }
    }
  }

  def getSourcesInFolder(token: String, folderID: String, size: Int, startPosition: Long
      ): fs2.Stream[IO, FolderSource] = timed {
    authorizer.checkFolderReadPermissionAsStream(token, folderID).flatMap { _ =>
      folderSourceDao.getSourcesByFolder(folderID, size, startPosition);
    }
  }

  def getSourcesInFolders(token: String, folderIDs: Seq[String], sizeInEachFolder: Int
      ): fs2.Stream[IO, FolderSource] = timed {
    authorizer.authorizeAsStream(token).flatMap { userSession =>
      folderDao
        .listByIDs(folderIDs)
        .filter(f => f.userID.equals(userSession.userID) || f.recommend)
        .flatMap(f => folderSourceDao.getSourcesByFolder(f.id, sizeInEachFolder, -1))
    }
  }

  def addSourceToFolder(token: String, folderID: String, sourceID: ID, position: Long): IO[FolderSource] = timed {
    authorizer.checkFolderPermission(token, folderID).flatMap { case (session, _) =>
      sourceDao.get(sourceID).flatMap {
        case Some(source) =>
          (if (source.fetchStatus == FetchStatus.PAUSED) {
            sourceDao.update(source.id, SourceUpdater(fetchStatus = Some(FetchStatus.SCHEDULED)))
          } else {
            IO.unit
          }) >>
            newFolderSourceMapping(session.userID, folderID, source, position).flatMap { mapping =>
              folderSourceDao.addSourceToFolder(mapping).map {
                case true => FolderSource(mapping, source)
                case false => throw SourceAlreadyInFolder(sourceID, folderID)
              }
            }
        case None => IO.raiseError(SourceNotFound(sourceID))
      }
    }
  }

  def moveSourceAfter(token: String, folderID: String, sourceID: ID, targetSourceID: ID): IO[Boolean] = timed {
    for {
      _ <- authorizer.checkFolderPermission(token, folderID)
      folderSourceOpt <- folderSourceDao.get(folderID, targetSourceID)
      folderSource <- if (folderSourceOpt.isDefined) IO.pure(folderSourceOpt.get)
        else IO.raiseError(SourceInFolderNotFoundError(sourceID, folderID))
      nextFolderSourceOpt <- folderSourceDao.getSourcesByFolder(
        folderID, 1, folderSource.folderMapping.position).compile.last
      result <- if (nextFolderSourceOpt.isDefined && nextFolderSourceOpt.get.source.id.equals(sourceID)) {
        logger.info(s"Found the same source ID when moving the source after, skip. " +
          s"From source: $sourceID, to source: $targetSourceID") >> IO.pure(false)
      } else {
        getPositionBetween(Some(folderSource), nextFolderSourceOpt, folderID).flatMap { nextPosition =>
          logger.info(s"Found the new position for source $sourceID when moving after $targetSourceID: $nextPosition.") >>
            folderSourceDao.updateSourceOrder(folderID, sourceID, nextPosition)
        }
      }
    } yield result
  }

  def moveSourceBefore(token: String, folderID: String, sourceID: ID, targetSourceID: ID): IO[Boolean] = timed {
    for {
      _ <- authorizer.checkFolderPermission(token, folderID)
      folderSourceOpt <- folderSourceDao.get(folderID, targetSourceID)
      folderSource <- if (folderSourceOpt.isDefined) IO.pure(folderSourceOpt.get)
        else IO.raiseError(SourceInFolderNotFoundError(sourceID, folderID))
      prevFolderSourceOpt <- folderSourceDao.getSourcesByFolderReverse(
        folderID, 1, folderSource.folderMapping.position).compile.last
      result <- if (prevFolderSourceOpt.isDefined && prevFolderSourceOpt.get.source.id.equals(sourceID)) {
        logger.info(s"Found the same source ID when moving the source before, skip. " +
          s"From source: $sourceID, to source: $targetSourceID") >> IO.pure(false)
      } else {
        getPositionBetween(prevFolderSourceOpt, Some(folderSource), folderID).flatMap { nextPosition =>
          logger.info(s"Found the new position for source $sourceID when moving before $targetSourceID: $nextPosition.") >>
            folderSourceDao.updateSourceOrder(folderID, sourceID, nextPosition)
        }
      }
    } yield result
  }


  private def getPositionBetween(sourceAOpt: Option[FolderSource], sourceBOpt: Option[FolderSource], folderID: String,
      retried: Int = 0): IO[Long] = {
    if (retried > 3) {
      IO.raiseError(SourcePositionCleanupFailed(folderID, retried))
    } else { (sourceAOpt, sourceBOpt) match {
      case (None, None) => IO.raiseError(new Exception("Wrong params: at least one source should be not none"))
      case (Some(sourceA), None) => IO.pure(sourceA.folderMapping.position + 1000)
      case (None, Some(sourceB)) if sourceB.folderMapping.position <= 1 =>
        nextGetPositionBetween(sourceAOpt, sourceBOpt, folderID, retried)
      case (None, Some(sourceB)) => IO.pure(sourceB.folderMapping.position / 2)
      case (Some(sourceA), Some(sourceB)) =>
        val diff = sourceB.folderMapping.position - sourceA.folderMapping.position
        if (diff <= 1) nextGetPositionBetween(sourceAOpt, sourceBOpt, folderID, retried)
          else IO(sourceA.folderMapping.position + diff / 2)
      }
    }
  }

  private def nextGetPositionBetween(sourceAOpt: Option[FolderSource], sourceBOpt: Option[FolderSource],
      folderID: String, retried: Int): IO[Long] = {
    for {
      _ <- folderSourceDao.cleanupPositionInFolder(folderID)
      nextA <- refreshSourceOpt(sourceAOpt)
      nextB <- refreshSourceOpt(sourceBOpt)
      result <- getPositionBetween(nextA, nextB, folderID, retried + 1)
    } yield result
  }

  private def refreshSourceOpt(sourceOpt: Option[FolderSource]): IO[Option[FolderSource]] = {
    if (sourceOpt.isDefined) folderSourceDao.get(sourceOpt.get.folderMapping.folderID, sourceOpt.get.source.id)
    else IO.pure(None)
  }

  def cleanupPositionInFolder(token: String, folderID: String): IO[Int] = timed {
    authorizer.checkFolderPermission(token, folderID).flatMap { _ =>
      folderSourceDao.cleanupPositionInFolder(folderID)
    }
  }

  def delSourceFromFolder(token: String, folderID: String, sourceID: ID): IO[Boolean] = timed {
    authorizer.checkFolderPermission(token, folderID).flatMap {_ =>
      folderSourceDao.delSourceFromFolder(folderID, sourceID)
    }
  }

  def updateSourceOrderInFolder(token: String, folderID: String, sourceID: ID, position: Long): IO[Boolean] = timed {
    authorizer.checkFolderPermission(token, folderID).flatMap { _ =>
      folderSourceDao.updateSourceOrder(folderID, sourceID, position)
    }
  }

  def delSourceForUser(token: String, sourceID: ID): IO[Unit] = timed {
    authorizer.authorize(token).flatMap { session =>
      folderSourceDao.delSourceForUser(session.userID, sourceID)
    }
  }

  def getSource(token: String, sourceID: ID): IO[Source] = timed {
    authorizer.authorize(token)
      .flatMap(_ => sourceDao.get(sourceID))
      .flatMap {
        case Some(source) => IO.pure(source)
        case None => IO.raiseError(SourceNotFound(sourceID))
      }
  }

  def getOrImportSource(token: String, url: String): IO[Source] = timed {
    authorizer.authorize(token).flatMap(_ => getOrImportSourceInner(url))
  }

  def importSource(token: String, url: String): IO[ID] = timed {
    authorizer
      .authorize(token)
      .flatMap { _ => fetcher.adhocFetch(url) }
      .map {
        case Some(err) => throw FetchSourceError(url, err)
        case None => SourceID(url)
      }
  }

  def updateSourceMapping(token: String, sourceID: ID,
      folderSourceMappingUpdater: FolderSourceMappingUpdater): IO[Int] = timed {
    authorizer.authorize(token).flatMap { session =>
      folderSourceDao.updateSourceInfo(session.userID, sourceID, folderSourceMappingUpdater)
    }
  }

  def findSource(token: String, url: String): fs2.Stream[IO, SourceResult] = timed {
    authorizer.authorizeAsStream(token).flatMap(_ =>
      seqToStream(sourceFinder.findSource(url)))
  }

  def replaceSourceInstance(token: String, oldInstance: String, newInstance: String, size: Int): IO[Int] = timed {
    authorizer.authorize(token).flatMap { session =>
      folderSourceDao.getSourcesByUser(session.userID, size).compile.toList.flatMap {
        _.map { folderSource =>
          val oldUrl = folderSource.source.xmlUrl
          val newUrl = oldUrl.replace(oldInstance, newInstance)
          if (newUrl.equals(oldUrl)) {
            IO.pure(None)
          } else {
            (for {
              _ <- logger.info(s"Try to verify source from $newUrl for replacing instance")
              source <- getOrImportSourceInner(newUrl)
              _ <- logger.info(s"Verified source from $newUrl for replacing instance")
              _ <- logger.info(s"Adding source ${source.id} from $newUrl to folder ${folderSource.folderMapping.folderID}")
              _ <- folderSourceDao.addSourceToFolder(folderSource.folderMapping.copy(sourceID = source.id))
              _ <- logger.info(s"Added source ${source.id} from $newUrl to folder ${folderSource.folderMapping.folderID}")
              _ <- logger.info(s"Deleting source ${folderSource.source.id} from ${folderSource.source.xmlUrl} " +
                s"from folder ${folderSource.folderMapping.folderID}")
              _ <- folderSourceDao.delSourceFromFolder(folderSource.folderMapping.folderID, folderSource.source.id)
              _ <- logger.info(s"Deleted source ${folderSource.source.id} from ${folderSource.source.xmlUrl} " +
                s"from folder ${folderSource.folderMapping.folderID}")
            } yield Some()).handleErrorWith { err =>
              logger.error(err)(s"Error when replace $oldInstance with $newInstance").map(_ => None)
            }
          }
        }.sequence.map {
          _.count(_.isDefined)
        }
      }
    }
  }

  private def newFolderSourceMapping(userID: String, folderID: String, source: Source,
      position: Long): IO[FolderSourceMapping] = {
    folderSourceDao.getSourcesByID(userID, source.id, size = 1).compile.toList.map(_.headOption).map {
      case None =>
        FolderSourceMapping(folderID, source.id, userID, position, customSourceName = None,
          showTitle = source.showTitle, showFullArticle = source.showFullArticle, showMedia = source.showMedia,
          articleOrder = source.articleOrder)
      case Some(folderSource) =>
        val mapping = folderSource.folderMapping
        FolderSourceMapping(folderID, source.id, userID, position, customSourceName = mapping.customSourceName,
          showTitle = mapping.showTitle, showFullArticle = mapping.showFullArticle, showMedia = mapping.showMedia,
          articleOrder =mapping.articleOrder)
    }
  }

  private def getOrImportSourceInner(url: String): IO[Source] = {
    sourceDao.get(SourceID(url))
      .flatMap { source =>
        Clock[IO].realTimeInstant.map { now =>
          val oneDayAgo = ZonedDateTime.ofInstant(now, ZoneId.systemDefault()).plusDays(-1)
          // fetch newest data for paused source
          if (source.isDefined &&
            (source.get.fetchCompletedAt.isEmpty || source.get.fetchCompletedAt.get.isBefore(oneDayAgo))
            && source.get.fetchStatus == FetchStatus.PAUSED) {
            None
          } else {
            source
          }
        }
      }.flatMap {
      case Some(source) => IO.pure(source)
      case None => fetcher.adhocFetch(url).flatMap {
        case Some(err) => IO.raiseError(FetchSourceError(url, err))
        case None => sourceDao.get(SourceID(url)).map(_.get)
      }
    }
  }

}
