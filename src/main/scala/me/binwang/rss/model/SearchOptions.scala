package me.binwang.rss.model

import java.time.ZonedDateTime

/**
 * Parameters for searching.
 *
 * @param query The search term. Support Elasticsearch query syntax.
 * @param start How many entries to skip.
 * @param limit How many entries to return at most.
 * @param sortByTime Whether sort by time. If false, the returned articles will be sorted by matching.
 * @param highlight Whether highlight the match parts for the returned article description.
 * @param postedAfter Filter articles that posted after this.
 * @param postedBefore Filter articles that posted before this.
 */
case class SearchOptions(
    query: String,
    start: Int,
    limit: Int,
    sortByTime: Boolean = true,
    highlight: Boolean = false,
    postedAfter: Option[ZonedDateTime] = None,
    postedBefore: Option[ZonedDateTime] = None,
)
