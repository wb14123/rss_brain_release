package me.binwang.rss.model

case class TermWeight(
    term: String,
    weight: Double,
)

case class TermWeights(
    terms: Seq[TermWeight],
)
