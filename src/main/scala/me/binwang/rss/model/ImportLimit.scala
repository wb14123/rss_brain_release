package me.binwang.rss.model

case class ImportLimit(
  paidFolderCount: Option[Int] = None,
  paidSourceCount: Option[Int] = None,
  freeFolderCount: Option[Int] = None,
  freeSourceCount: Option[Int] = None,
)
