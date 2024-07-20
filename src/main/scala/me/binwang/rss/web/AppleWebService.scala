package me.binwang.rss.web

import cats.effect.IO
import me.binwang.rss.service.ApplePaymentService
import org.http4s.HttpRoutes
import org.http4s.dsl.io._

class AppleWebService(applePaymentService: ApplePaymentService) {
  private def handler(isSandBox: Boolean, body: IO[String]) = {
    Ok(body.flatMap(applePaymentService.inAppPurchaseCallback(_, isSandBox)))
  }

  val route: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "apple" / "payment_notification" =>
      handler(isSandBox = false, req.as[String])
    case req @ POST -> Root / "apple" / "payment_notification_sandbox" =>
      handler(isSandBox = true, req.as[String])
  }
}
