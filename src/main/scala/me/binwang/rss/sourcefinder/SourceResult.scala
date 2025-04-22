package me.binwang.rss.sourcefinder

/**
 * The result of finding sources by any URL.
 *
 * @param url The feed URL that has been found.
 * @param recommend Prefer the found URL over original URL even if the original URL is a valid one.
 * @param recommendReason The reason for prefer this over the original URL.
 */
case class SourceResult(
    url: String,
    recommend: Boolean = false, // recommend this source over what user has been inputted
    recommendReason: Option[String] = None,
)
