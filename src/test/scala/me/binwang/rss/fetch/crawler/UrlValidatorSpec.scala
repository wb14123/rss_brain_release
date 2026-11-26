package me.binwang.rss.fetch.crawler

import cats.effect.unsafe.IORuntime
import me.binwang.rss.model.SsrfBlockedError
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class UrlValidatorSpec extends AnyFunSpec with Matchers {

  implicit val ioRuntime: IORuntime = IORuntime.global

  val validator = new UrlValidator()

  describe("UrlValidator") {

    describe("scheme validation") {
      it("should allow http") {
        validator.validate("http://example.com/feed").unsafeRunSync()
      }

      it("should allow https") {
        validator.validate("https://example.com/feed").unsafeRunSync()
      }

      it("should block ftp") {
        val err = intercept[SsrfBlockedError] {
          validator.validate("ftp://example.com/feed").unsafeRunSync()
        }
        err.reason should include("disallowed scheme")
      }

      it("should block file") {
        intercept[SsrfBlockedError] {
          validator.validate("file:///etc/passwd").unsafeRunSync()
        }
      }
    }

    describe("private IP blocking") {
      it("should block 127.0.0.1 (loopback)") {
        val err = intercept[SsrfBlockedError] {
          validator.validate("http://127.0.0.1/feed").unsafeRunSync()
        }
        err.reason should include("private address")
      }

      it("should block 10.0.0.1 (site-local)") {
        val err = intercept[SsrfBlockedError] {
          validator.validate("http://10.0.0.1/feed").unsafeRunSync()
        }
        err.reason should include("private address")
      }

      it("should block 172.16.0.1 (site-local)") {
        val err = intercept[SsrfBlockedError] {
          validator.validate("http://172.16.0.1/feed").unsafeRunSync()
        }
        err.reason should include("private address")
      }

      it("should block 192.168.1.1 (site-local)") {
        val err = intercept[SsrfBlockedError] {
          validator.validate("http://192.168.1.1/feed").unsafeRunSync()
        }
        err.reason should include("private address")
      }

      it("should block 169.254.169.254 (link-local / cloud metadata)") {
        val err = intercept[SsrfBlockedError] {
          validator.validate("http://169.254.169.254/latest/meta-data/").unsafeRunSync()
        }
        err.reason should include("private address")
      }

      it("should block 0.0.0.0 (any-local)") {
        val err = intercept[SsrfBlockedError] {
          validator.validate("http://0.0.0.0/feed").unsafeRunSync()
        }
        err.reason should include("private address")
      }

      it("should block localhost") {
        val err = intercept[SsrfBlockedError] {
          validator.validate("http://localhost/feed").unsafeRunSync()
        }
        err.reason should include("private address")
      }

      it("should block [::1] (IPv6 loopback)") {
        val err = intercept[SsrfBlockedError] {
          validator.validate("http://[::1]/feed").unsafeRunSync()
        }
        err.reason should include("private address")
      }
    }

    describe("malformed URLs") {
      it("should block URL with no host") {
        val err = intercept[SsrfBlockedError] {
          validator.validate("http:///path").unsafeRunSync()
        }
        err.reason should include("missing host")
      }
    }

    describe("port validation") {
      it("should reject port 0") {
        val err = intercept[SsrfBlockedError] {
          validator.validate("http://example.com:0/feed").unsafeRunSync()
        }
        err.reason shouldBe "invalid port: 0"
      }

      it("should reject a port above 65535") {
        val err = intercept[SsrfBlockedError] {
          validator.validate("http://example.com:70000/feed").unsafeRunSync()
        }
        err.reason shouldBe "invalid port: 70000"
      }
    }

    describe("host allowlist") {
      it("should allow an explicitly listed host and port") {
        val v = new UrlValidator(Seq("feed-service.internal:8080"))
        v.validate("http://feed-service.internal:8080/feed").unsafeRunSync()
      }

      it("should allow https on the default port 443") {
        val v = new UrlValidator(Seq("feed-service.internal:443"))
        v.validate("https://feed-service.internal/feed").unsafeRunSync()
      }

      it("should allow http on the default port 80") {
        val v = new UrlValidator(Seq("feed-service.internal:80"))
        v.validate("http://feed-service.internal/feed").unsafeRunSync()
      }

      it("should allow every port listed for the same host") {
        val v = new UrlValidator(Seq("feed-service.internal:80", "feed-service.internal:8080"))
        v.validate("http://feed-service.internal:80/feed").unsafeRunSync()
        v.validate("http://feed-service.internal:8080/feed").unsafeRunSync()
      }

      it("should block a port that is not listed") {
        val v = new UrlValidator(Seq("feed-service.internal:443"))
        val err = intercept[SsrfBlockedError] {
          v.validate("https://feed-service.internal:8443/feed").unsafeRunSync()
        }
        err.reason shouldBe "port 8443 not allowed for host feed-service.internal (allowed: 443)"
      }

      it("should list all allowed ports in numeric order") {
        val v = new UrlValidator(Seq("feed-service.internal:443", "feed-service.internal:80"))
        val err = intercept[SsrfBlockedError] {
          v.validate("http://feed-service.internal:8080/feed").unsafeRunSync()
        }
        err.reason shouldBe "port 8080 not allowed for host feed-service.internal (allowed: 80, 443)"
      }

      it("should block an unlisted port of a public host") {
        val v = new UrlValidator(Seq("example.com:443"))
        val err = intercept[SsrfBlockedError] {
          v.validate("https://example.com:8443/feed").unsafeRunSync()
        }
        err.reason shouldBe "port 8443 not allowed for host example.com (allowed: 443)"
      }

      it("should block an unlisted port of an allowlisted private IP") {
        val v = new UrlValidator(Seq("192.168.1.1:9030"))
        val err = intercept[SsrfBlockedError] {
          v.validate("http://192.168.1.1:80/feed").unsafeRunSync()
        }
        err.reason shouldBe "port 80 not allowed for host 192.168.1.1 (allowed: 9030)"
      }

      it("should allow an allowlisted private IP on its port") {
        val v = new UrlValidator(Seq("10.88.111.1:9030"))
        v.validate("http://10.88.111.1:9030/feed").unsafeRunSync()
      }

      it("should ignore the case of the allowlist entry") {
        val v = new UrlValidator(Seq("Feed-Service.Internal:443"))
        v.validate("https://feed-service.internal/feed").unsafeRunSync()
      }

      it("should ignore the case of the request host") {
        val v = new UrlValidator(Seq("feed-service.internal:443"))
        v.validate("https://Feed-Service.Internal/feed").unsafeRunSync()
      }

      it("should match a request host with a trailing dot") {
        val v = new UrlValidator(Seq("feed-service.internal:443"))
        v.validate("https://feed-service.internal./feed").unsafeRunSync()
      }

      it("should match an allowlist entry with a trailing dot") {
        val v = new UrlValidator(Seq("feed-service.internal.:443"))
        v.validate("https://feed-service.internal/feed").unsafeRunSync()
      }

      it("should still block private IPs that are not in the allowlist") {
        val v = new UrlValidator(Seq("feed-service.internal:8080"))
        val err = intercept[SsrfBlockedError] {
          v.validate("http://192.168.1.1/feed").unsafeRunSync()
        }
        err.reason should include("private address")
      }

      it("should not restrict the ports of hosts that are not in the allowlist") {
        val v = new UrlValidator(Seq("feed-service.internal:8080"))
        v.validate("http://example.com:6379/feed").unsafeRunSync()
      }
    }

    describe("allowlist entry parsing") {
      val invalidEntries = Seq(
        "localhost",
        "host:0",
        "host:70000",
        "*.cluster.local:443",
        "https://host:443",
        "host:",
        ":80",
        "host:abc",
        "host:443/path",
        " host:443",
        "",
      )

      invalidEntries.foreach { entry =>
        it(s"should reject '$entry'") {
          val err = intercept[IllegalArgumentException] {
            new UrlValidator(Seq(entry))
          }
          err.getMessage should include(s"'$entry'")
        }
      }
    }

    describe("allowAll") {
      it("should allow anything") {
        UrlValidator.allowAll.validate("http://127.0.0.1/feed").unsafeRunSync()
        UrlValidator.allowAll.validate("ftp://anything").unsafeRunSync()
      }
    }
  }
}
