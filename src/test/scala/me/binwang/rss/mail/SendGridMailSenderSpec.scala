package me.binwang.rss.mail

import cats.effect.unsafe.IORuntime
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class SendGridMailSenderSpec extends AnyFunSpec with BeforeAndAfterEach with Matchers {

  implicit val ioRuntime: IORuntime = IORuntime.global

  describe("send grid mail send") {

    // ignore this test since it will use SendGrid quote. Change `ignore` to `it` to enable it.
    ignore ("should send password reset mail") {
      val mailSender = new SendGridMailSender()
      mailSender.sendResetPassword("bin.wang@mail.binwang.me", "https://app.rssbrain.com", "1 hour").unsafeRunSync()
    }

  }

}
