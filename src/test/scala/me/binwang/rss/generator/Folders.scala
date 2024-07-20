package me.binwang.rss.generator

import me.binwang.rss.model.{Folder, FolderCreator}
import org.scalacheck.Gen

object Folders {

  def get(userID: String, position: Int, isUserDefault: Boolean = false): Folder = {
    val genFolder = for {
      id <- Gen.uuid
      name <- Gen.asciiPrintableStr
      description <- Gen.asciiPrintableStr
    } yield Folder(
      id = id.toString,
      userID = userID,
      name = name,
      description = Some(description),
      position = position,
      count = 0,
      isUserDefault = isUserDefault,
    )
    genFolder.sample.get
  }

  def getCreator(position: Int): FolderCreator = {
    val genFolder = for {
      name <- Gen.asciiPrintableStr
      description <- Gen.asciiPrintableStr
    } yield FolderCreator(
      name = name,
      description = Some(description),
      position = position,
    )
    genFolder.sample.get
  }

}
