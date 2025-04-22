package me.binwang.rss.model

/**
 * A structure to have both folderSourceMapping (the source metadata in a folder) and the actual source.
 */
case class FolderSource(
  folderMapping: FolderSourceMapping,
  source: Source,
)
