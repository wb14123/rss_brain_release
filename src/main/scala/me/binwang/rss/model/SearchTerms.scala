package me.binwang.rss.model

/**
 * Suggested search terms.
 *
 * @param terms each element is a search term that can be used in search engines.
 */
case class SearchTerms(terms: Seq[String])
