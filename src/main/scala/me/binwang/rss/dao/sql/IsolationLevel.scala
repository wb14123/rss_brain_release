package me.binwang.rss.dao.sql

object IsolationLevel {
  val SERIALIZABLE = "TRANSACTION_SERIALIZABLE"
  val READ_COMMITTED = "TRANSACTION_READ_COMMITTED"
}
