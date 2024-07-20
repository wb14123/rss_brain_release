package me.binwang.rss.model

import java.time.ZonedDateTime

case class SearchOptions(
    query: String,
    start: Int,
    limit: Int,
    sortByTime: Boolean = true,
    highlight: Boolean = false,
    postedAfter: Option[ZonedDateTime] = None,
    postedBefore: Option[ZonedDateTime] = None,
)
