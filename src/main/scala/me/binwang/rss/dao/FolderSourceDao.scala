package me.binwang.rss.dao

import cats.effect.IO
import me.binwang.rss.model.{FolderSource, FolderSourceMapping, FolderSourceMappingUpdater}
import me.binwang.rss.model.ID.ID

trait FolderSourceDao {
  def createTable(): IO[Unit]
  def dropTable(): IO[Unit]
  def addSourceToFolder(folderSourceMapping: FolderSourceMapping): IO[Boolean]
  def delSourceFromFolder(folderID: String, sourceID: ID): IO[Boolean]
  def delAllInFolder(folderID: String): IO[Unit]
  def delSourceForUser(userID: String, sourceID: ID): IO[Unit]
  def deleteAllForUser(userID: String): IO[Long]
  def updateSourceOrder(folderID: String, sourceID: ID, position: Long): IO[Boolean]
  def get(folderID: String, sourceID: ID): IO[Option[FolderSource]]
  def getSourcesByFolder(folderID: String, size: Int = 1000, startPosition: Long = -1): fs2.Stream[IO, FolderSource]
  def getSourcesByFolderReverse(folderID: String, size: Int = 1000, startPosition: Long): fs2.Stream[IO, FolderSource]
  def getSourcesByUser(userID: String, size: Int = 1000, startPosition: Long = 0): fs2.Stream[IO, FolderSource]
  def getSourcesByID(userID: String, sourceID: ID, size: Int = 1000, skip: Int = 0): fs2.Stream[IO, FolderSource]
  def copySources(fromFolderID: String, toFolderID: String): IO[Int]
  def cleanupPositionInFolder(folderID: String): IO[Int]
  def updateSourceInfo(userID: String, sourceID: ID, folderSourceMappingUpdater: FolderSourceMappingUpdater): IO[Int]
  def getSourcesByUserAndPattern(userID: String, pattern: String, size: Int): fs2.Stream[IO, FolderSource]
}
