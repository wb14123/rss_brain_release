package me.binwang.rss.dao.sql

import cats.effect.{IO, Resource}
import com.typesafe.config.ConfigFactory
import com.zaxxer.hikari.HikariConfig
import doobie.hikari.HikariTransactor

object ConnectionPool {
  def apply(): Resource[IO, ConnectionPool] = {

    val config = ConfigFactory.load()

    val hikariConfig = new HikariConfig()
    hikariConfig.setDriverClassName("org.postgresql.Driver")
    hikariConfig.setJdbcUrl(config.getString("db.url"))
    hikariConfig.setUsername(config.getString("db.user"))
    hikariConfig.setPassword(config.getString("db.password"))
    hikariConfig.setMaximumPoolSize(config.getInt("db.maxPoolSize"))
    hikariConfig.setMinimumIdle(config.getInt("db.minIdle"))

    HikariTransactor.fromHikariConfig[IO](hikariConfig).map(ConnectionPool(_))
  }
}

case class ConnectionPool(xa: HikariTransactor[IO])
