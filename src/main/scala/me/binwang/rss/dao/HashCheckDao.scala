package me.binwang.rss.dao

import cats.effect.IO

trait HashCheckDao[T] {

  def exists(obj: T): IO[Boolean]
  def insertOrUpdate(obj: T): IO[Unit]
  def dropTable(): IO[Unit]

}
