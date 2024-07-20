package me.binwang.rss.parser

import scala.language.implicitConversions
import scala.xml._

object XmlNamespace {
  val itunes =  "http://www.itunes.com/dtds/podcast-1.0.dtd"
  val media = "http://search.yahoo.com/mrss/"
  val rsshub = "http://rsshub.app/xml/schemas"

  implicit def toNodesNamespace(nodes: Seq[Node]): NodesNamespace = new NodesNamespace(nodes)
}


class NodesNamespace(val nodes: Seq[Node]) {
  def filterNamespace(namespaceUrl: String): Seq[Node] = {
    nodes.filter(n => n.namespace != null && n.namespace.equals(namespaceUrl))
  }
}
