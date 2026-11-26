package me.binwang.rss.dao.sql

import cats.effect.{IO, Resource}
import com.typesafe.config.ConfigFactory
import com.zaxxer.hikari.HikariConfig
import doobie.hikari.HikariTransactor

object ConnectionPool {

  private def hikariConfig(isolationLevel: String) = {
    val config = ConfigFactory.load()

    val hikariConfig = new HikariConfig()
    hikariConfig.setDriverClassName("org.postgresql.Driver")
    hikariConfig.setJdbcUrl(config.getString("db.url"))
    hikariConfig.setUsername(config.getString("db.user"))
    hikariConfig.setPassword(config.getString("db.password"))
    hikariConfig.setMaximumPoolSize(config.getInt("db.maxPoolSize"))
    hikariConfig.setMinimumIdle(config.getInt("db.minIdle"))
    hikariConfig.setTransactionIsolation(isolationLevel)
    hikariConfig
  }

  def apply(): Resource[IO, ConnectionPool] = {
    for {
      xa <- HikariTransactor.fromHikariConfig[IO](hikariConfig(IsolationLevel.SERIALIZABLE))
      readCommitedXa <- HikariTransactor.fromHikariConfig[IO](hikariConfig(IsolationLevel.READ_COMMITTED))
    } yield ConnectionPool(xa, readCommitedXa)
  }
}

case class ConnectionPool(xa: HikariTransactor[IO], readCommitedXa: HikariTransactor[IO])
