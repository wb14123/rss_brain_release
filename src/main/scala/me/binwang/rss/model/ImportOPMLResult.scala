package me.binwang.rss.model

/**
 * The result of importing sources from an OPML file.
 *
 * @param totalSources How many distinct feed urls the file contains.
 * @param successfulSources How many of them are subscribed by the user after the import.
 * @param failedSources The feeds that could not be added.
 */
case class ImportOPMLResult(
  totalSources: Int,
  successfulSources: Int,
  failedSources: Seq[ImportFailedSource],
)

/**
 * The details of a source that couldn't be added by an OPML import.
 *
 * @param xmlUrl The feed url.
 * @param error The user facing error message.
 */
case class ImportFailedSource(
  xmlUrl: String,
  error: String,
)
