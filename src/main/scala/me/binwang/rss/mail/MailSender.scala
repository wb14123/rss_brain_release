package me.binwang.rss.mail

import cats.effect.IO

abstract class MailSender {

  val fromNoReply = "no-reply@rssbrain.com"

  def sendMail(from: String, to: String, subject: String, content: String): IO[Unit]

  def sendResetPassword(to: String, link: String, expireTime: String): IO[Unit] = {
    val subject = "Reset password for RSS Brain"
    val content = s"""
      |<p>You requested to reset password for RSS Brain. Click <a href="$link">this link</a> to continue.</p>
      |<p>If you cannot click on the link, copy the url $link to browser and open it.</p>
      |<p>This link will be expired in $expireTime.</p>
      |""".stripMargin
    sendMail(fromNoReply, to, subject, content)
  }

  def sendActiveAccount(to: String, link: String): IO[Unit] = {
    val subject = "Active RSS Brain Account"
    val content = s"""
      |<p>Click <a href="$link">this link</a> to active your RSS Brain account.</p>
      |<p>If you cannot click on the link, copy the url $link to browser and open it.</p>
      |""".stripMargin
    sendMail(fromNoReply, to, subject, content)
  }

  def sendPaymentFailed(to: String): IO[Unit] = {
    val subject = "RSS Brain Payment Failed"
    val content =
      """
        |<p>Your payment to RSS Brain is failed. Please check your payment method at
        |<a href="https://app.rssbrain.com">https://app.rssbrain.com</a>
        |so that you can continue using RSS Brain.</p>""".stripMargin
    sendMail(fromNoReply, to, subject, content)
  }

  def sendUserDeleteConfirm(to: String, link: String): IO[Unit] = {
    val subject = "RSS Brain User Data Delete Confirmation"
    val content =
      s"""
        |<p>We have received your request to delete all of your data from RSS Brain.
        |Please make sure you've cancelled all of your
        |payment first. Once the data is deleted, there is no way to recover them.</p>
        |
        |<p>If you still want to delete the your data. Please go to <a href="$link">$link</a> to continue.
        |The link will be expired after 72 hours.</p>
        |
        |""".stripMargin
    sendMail(fromNoReply, to, subject, content)
  }


}
