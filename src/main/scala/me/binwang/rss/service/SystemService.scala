package me.binwang.rss.service

import cats.effect.IO
import com.typesafe.config.ConfigFactory

class SystemService {

  private val apiVersion = ConfigFactory.load().getString("api.version")

  def getApiVersion(): IO[String] = {
    IO.pure(apiVersion)
  }

  def versionIsCompatible(version: String): IO[Boolean] = {
    val clientVersions = version.split(".")
    val serverVersions = apiVersion.split(".")
    if (clientVersions.length != 3) {
      IO.pure(false)
    } else if (clientVersions(0) < serverVersions(0) || clientVersions(1) < serverVersions(1)) {
      IO.pure(false)
    } else {
      IO.pure(true)
    }
  }

}
