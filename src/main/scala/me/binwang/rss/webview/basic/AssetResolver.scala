package me.binwang.rss.webview.basic

import scala.io.Source
import scala.util.Using

object AssetResolver {

  private val basePath = "/static/dist"

  private val assetNames = Seq("main.css", "main.js")

  private val hashes: Map[String, String] = {
    assetNames.flatMap { baseName =>
      Option(getClass.getResourceAsStream(s"/webview/static/dist/$baseName.hash"))
        .flatMap(is => Using(is)(Source.fromInputStream(_, "UTF-8").mkString.trim).toOption)
        .filter(_.nonEmpty)
        .map(baseName -> _)
    }.toMap
  }

  def url(baseName: String): String = {
    hashes.get(baseName) match {
      case Some(hash) => s"$basePath/$hash.$baseName"
      case None        => s"$basePath/$baseName"
    }
  }
}
