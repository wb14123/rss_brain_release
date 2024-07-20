package me.binwang.rss.service

import cats.effect.IO
import me.binwang.archmage.core.CatsMacros.timed
import me.binwang.rss.dao.MoreLikeThisMappingDao
import me.binwang.rss.metric.TimeMetrics
import me.binwang.rss.model.MoreLikeThisType.MoreLikeThisType
import me.binwang.rss.model.{MoreLikeThisMapping, MoreLikeThisType}

class MoreLikeThisService(
    private val moreLikeThisMappingDao: MoreLikeThisMappingDao,
    private val authorizer: Authorizer,
) extends TimeMetrics {


  def getMoreLikeThisMappings(token: String, fromID: String, fromType: MoreLikeThisType, size: Int,
      startPosition: Long): fs2.Stream[IO, MoreLikeThisMapping] = timed {
    /*
     User can only add a mapping to folder if it already has permission, and we are filtering mappings by user here,
     so no need to check folder permission
     */
    authorizer.authorizeAsStream(token).flatMap { session =>
      moreLikeThisMappingDao.listByFromID(fromID = fromID, fromType = fromType,
        userID = session.userID, size, startPosition)
    }
  }

  def addMoreLikeThisMapping(token: String, fromID: String, fromType: MoreLikeThisType, moreLikeThisID: String,
      moreLikeThisType: MoreLikeThisType, position: Long): IO[Boolean] = timed {
    val auth = if (moreLikeThisType == MoreLikeThisType.FOLDER) {
      authorizer.checkFolderPermission(token, moreLikeThisID).map(_._1)
    } else {
      authorizer.authorize(token)
    }.flatMap { firstAuth =>
      if (fromType == MoreLikeThisType.FOLDER) {
        authorizer.checkFolderPermission(token, fromID).map(_._1)
      } else {
        IO.pure(firstAuth)
      }
    }
    auth.flatMap { session =>
      moreLikeThisMappingDao.insertIfNotExists(MoreLikeThisMapping(
        fromID = fromID,
        fromType = fromType,
        moreLikeThisID = moreLikeThisID,
        moreLikeThisType = moreLikeThisType,
        userID = session.userID,
        position = position
      ))
    }
  }

  def delMoreLikeThisMapping(token: String, fromID: String, fromType: MoreLikeThisType, moreLikeThisID: String,
      moreLikeThisType: MoreLikeThisType): IO[Boolean] = timed {
    authorizer.authorize(token).flatMap { session =>
      moreLikeThisMappingDao.delete(MoreLikeThisMapping(
        fromID = fromID,
        fromType = fromType,
        moreLikeThisID = moreLikeThisID,
        moreLikeThisType = moreLikeThisType,
        userID = session.userID,
        position = 0 // doesn't matter
      ))
    }
  }

  def updateMoreLikeThisMapping(token: String, fromID: String, fromType: MoreLikeThisType, moreLikeThisID: String,
      moreLikeThisType: MoreLikeThisType, position: Long): IO[Boolean] = timed {
    authorizer.authorize(token).flatMap { session =>
      moreLikeThisMappingDao.updatePosition(MoreLikeThisMapping(
        fromID = fromID,
        fromType = fromType,
        moreLikeThisID = moreLikeThisID,
        moreLikeThisType = moreLikeThisType,
        userID = session.userID,
        position = position // doesn't matter
      ))
    }
  }

  def cleanupMappingsPosition(token: String, fromID: String, fromType: MoreLikeThisType): IO[Unit] = timed {
    authorizer.authorize(token).flatMap { session =>
      moreLikeThisMappingDao.cleanupPosition(fromID, fromType, session.userID).map(_ => ())
    }
  }

}
