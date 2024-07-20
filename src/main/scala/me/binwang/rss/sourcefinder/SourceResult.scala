package me.binwang.rss.sourcefinder

case class SourceResult(
    url: String,
    recommend: Boolean = false, // recommend this source over what user has been inputted
    recommendReason: Option[String] = None,
)
