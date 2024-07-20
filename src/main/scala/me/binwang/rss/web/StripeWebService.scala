package me.binwang.rss.web

import cats.effect.IO
import me.binwang.rss.service.StripePaymentService
import org.http4s.HttpRoutes
import org.http4s.dsl.io._
import org.typelevel.ci.CIString

class StripeWebService(stripePaymentService: StripePaymentService) {
  val route: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "stripe" / "payment_webhook" =>
      req.headers.get(CIString("Stripe-Signature")) match {
        case None => BadRequest()
        case Some(header) =>
          val result = for {
            payload <- req.as[String]
            sigHeader = header.head.sanitizedValue
            _ <- stripePaymentService.paymentCallback(payload, sigHeader)
          } yield ()
          Ok(result)
      }
  }

}
