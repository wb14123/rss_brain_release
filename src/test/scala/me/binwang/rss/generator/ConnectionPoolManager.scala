package me.binwang.rss.generator

import cats.effect.unsafe.IORuntime
import me.binwang.rss.dao.sql.ConnectionPool

object ConnectionPoolManager {
  implicit val ioRuntime: IORuntime = IORuntime.global
  implicit val connectionPool: ConnectionPool = ConnectionPool().allocated.unsafeRunSync()._1
}
