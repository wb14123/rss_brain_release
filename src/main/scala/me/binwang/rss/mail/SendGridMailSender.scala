package me.binwang.rss.mail
import cats.effect.IO
import com.sendgrid.helpers.mail.Mail
import com.sendgrid.helpers.mail.objects.{Content, Email}
import com.sendgrid.{Method, Request, SendGrid}
import com.typesafe.config.ConfigFactory
import me.binwang.rss.model.SendMailException

class SendGridMailSender extends MailSender {

  private val apiKey = ConfigFactory.load().getString("sendgrid.apiKey")

  override def sendMail(from: String, to: String, subject: String, contentStr: String): IO[Unit] =  {
    val content = new Content("text/html", contentStr)
    val mail = new Mail(new Email(from), subject, new Email(to), content)

    val sg = new SendGrid(apiKey)
    val request = new Request()
    request.setMethod(Method.POST)
    request.setEndpoint("mail/send")
    request.setBody(mail.build)
    IO(sg.api(request))
      .handleErrorWith(e => IO.raiseError(SendMailException("Send mail failed", Some(e))))
      .flatMap { resp =>
        if (resp.getStatusCode < 200 || resp.getStatusCode >= 300) {
          val errorMsg = s"Send mail failed, status: ${resp.getStatusCode}, body: ${resp.getBody}"
          IO.raiseError(SendMailException(errorMsg))
        } else {
          IO.unit
        }
      }
  }
}
