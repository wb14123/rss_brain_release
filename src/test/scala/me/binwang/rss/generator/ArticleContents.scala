package me.binwang.rss.generator

import me.binwang.rss.model.ID.ID
import me.binwang.rss.model.{ArticleContent, ID}
import org.scalacheck.Gen

object ArticleContents {

  def get(id: Option[ID] = None): ArticleContent = {
    ArticleContent(
      id = id.getOrElse(ID.hash(Gen.alphaStr.sample.get)),
      content = Gen.asciiPrintableStr.sample.get)
  }

}
