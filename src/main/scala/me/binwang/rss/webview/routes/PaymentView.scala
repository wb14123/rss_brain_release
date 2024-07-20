package me.binwang.rss.webview.routes

import cats.effect.IO
import com.typesafe.config.ConfigFactory
import me.binwang.rss.model.PaymentCustomerNotFound
import me.binwang.rss.service.{StripePaymentService, UserService}
import me.binwang.rss.webview.auth.CookieGetter.reqToCookieGetter
import me.binwang.rss.webview.basic.ContentRender.wrapContentRaw
import me.binwang.rss.webview.basic.{Html, HttpResponse, ScalatagsSeqInstances}
import me.binwang.rss.webview.widgets.{LogoHeader, PageHeader}
import org.http4s.dsl.io._
import org.http4s.headers.`Content-Type`
import org.http4s.scalatags.ScalatagsInstances
import org.http4s.{HttpRoutes, MediaType, Request}
import scalatags.Text.all._

class PaymentView(userService: UserService, stripePaymentService: StripePaymentService) extends Http4sView
    with ScalatagsInstances with ScalatagsSeqInstances {

  private val websiteBaseUrl = ConfigFactory.load().getString("website.baseUrl")

  private def createCheckoutUrl(req: Request[IO]) = {
    val successUrl = s"$websiteBaseUrl/payment/success"
    val cancelUrl =  s"$websiteBaseUrl/payment/cancel"
    val token = req.authToken
    stripePaymentService.createCheckoutSession(token, successUrl, cancelUrl, needRedirect = false).flatMap { link =>
      HttpResponse.redirect("go to checkout", link, req)
    }
  }

  override val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case req @ GET -> Root / "payment" / "stripe" / "checkout" =>
      createCheckoutUrl(req)

    case req @ GET -> Root / "payment" / "stripe" / "portal" =>
      val token = req.authToken
      stripePaymentService.createPortalLink(token,
          s"$websiteBaseUrl/payment/success", needRedirect = false).flatMap { link =>
        HttpResponse.redirect("go to portal", link, req)
      }.handleErrorWith {
        case PaymentCustomerNotFound(_) => createCheckoutUrl(req)
      }

    case req @ GET -> Root / "payment" / "success" => wrapContentRaw(req) {
      val dom = Seq(
        PageHeader(Some("Payment Successful")),
        div(
          cls := "form-body",
          "Payment updated successfully. You can check the details in settings.",
        )
      )
      Ok(dom, `Content-Type`(MediaType.text.html))
    }

    case req @ GET -> Root / "payment" / "cancel" => wrapContentRaw(req) {
      val dom = Seq(
        PageHeader(Some("Payment Successful")),
        div(
          cls := "form-body",
          "Payment canceled. You can check the details in settings.",
        )
      )
      Ok(dom, `Content-Type`(MediaType.text.html))
    }

    case req @ GET -> Root / "payment" / "renew" =>
      val token = req.authToken
      for {
        userInfo <- userService.getMyUserInfo(token)
        nowInstant <- IO.realTimeInstant
        res <- if (userInfo.subscribeEndAt.toInstant.isAfter(nowInstant)) {
          HttpResponse.redirect("refresh token", "/login", req)
        } else {
          val dom = div(
            cls := "login-page",
            LogoHeader(),
            div(
              p(
                "Your subscription has ended. Please click ",
                a(href := "/payment/stripe/portal", "here"),
                " to manage your current subscription. Or click ",
                a(href := "/payment/stripe/checkout", "here"),
                " to create a new subscription."
              ),
              p("If you still see this message after the payment, please try to ",
                a(href := "/login", "login"), " again to refresh the session."),
              p("If you still have any question, feel free to contact us at ",
                a(href := "mailto:customer-service@rssbrain.com", "customer-service@rssbrain.com"),
                ".",
              )
            )
          )
          Ok(Html(dom), `Content-Type`(MediaType.text.html))
        }
      } yield res

  }


}
