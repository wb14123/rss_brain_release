package me.binwang.rss.dao

import cats.effect.IO
import me.binwang.rss.model.{Folder, FolderUpdater}

trait FolderDao {

  def getByID(folderID: String): IO[Option[Folder]]
  def listByIDs(folderIDs: Seq[String]): fs2.Stream[IO, Folder]
  def listByUser(userID: String, size: Int = 100, startPosition: Long = 0): fs2.Stream[IO, Folder]
  def listRecommended(lang: String, size: Int = 20, startPosition: Long = 0): fs2.Stream[IO, Folder]
  def getByUserAndName(userID: String, name: String): IO[Option[Folder]]
  def getUserDefaultFolder(userID: String): IO[Option[Folder]]
  def insertIfNotExist(folder: Folder): IO[Boolean]
  def update(folderID: String, updater: FolderUpdater): IO[Boolean]
  def delete(folderID: String): IO[Boolean]
  def cleanupPositionForUser(userID: String): IO[Int]
  def deleteAllForUser(userID: String): IO[Long]

}
