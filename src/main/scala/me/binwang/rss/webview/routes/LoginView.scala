package me.binwang.rss.webview.routes

import cats.effect._
import me.binwang.rss.service.UserService
import me.binwang.rss.webview.auth.CookieGetter.reqToCookieGetter
import me.binwang.rss.webview.basic.ScalaTagAttributes._
import me.binwang.rss.webview.basic.{Html, HttpResponse}
import me.binwang.rss.webview.widgets.LogoHeader
import org.http4s.dsl.io._
import org.http4s.headers._
import org.http4s.scalatags.ScalatagsInstances
import scalatags.Text.all._
import org.http4s.{HttpRoutes, _}

import scala.concurrent.duration.DurationInt

class LoginView(userService: UserService) extends Http4sView with ScalatagsInstances {
  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case req @ GET -> Root / "login" =>
      val redirectParam = req.params.get("redirect_url").map(url => s"?redirect_url=$url").getOrElse("")
      val body =
        form(
          cls := "login-page",
          LogoHeader(),
          div(
            if (req.params.contains("after-active"))
              label("Your account has been activated. You can login here now.") else "",
            cls := "login-inputs",
            input(`type` := "text", name := "email", id := "email", placeholder := "Email", required),
            input(`type` := "password", name := "password", id := "password", placeholder := "Password", required),
          ),
          button(hxPost := s"/hx/login$redirectParam", hxDisableThis, "Login"),
          div(
            cls := "login-hint",
            small("Don't have an account yet? Click ", a(href := "/signup")("here"), " to create a new account."),
            small("Forgot password? Click ",
              a(href := "/reset-password-request", "here"),
              " to reset your password."),
          ),
        )
      Ok(Html(body), `Content-Type`(MediaType.text.html))

    case req @ POST -> Root / "hx" / "login" =>
      val redirectUrl = req.params.getOrElse("redirect_url", "/")
      req.decode[UrlForm] { data =>
        userService.login(
          data.values.get("email").flatMap(_.headOption).get,
          data.values.get("password").flatMap(_.headOption).get,
        ).flatMap { session =>
          HttpResponse.redirect("logged in", redirectUrl, req).map(_.addCookie(ResponseCookie(
            "token",
            session.token,
            path = Some("/"),
            secure = true,
            httpOnly = true,
            sameSite = Some(SameSite.Lax),
            maxAge = Some(7.days.toSeconds),
          )))
        }
      }

    case req @ GET -> Root / "llm_auth" =>
      val token = req.authToken
      val body = div(s"Use `token` param in the APIs to do authentication. Your current token is `$token`.")
      Ok(Html(body), `Content-Type`(MediaType.text.html))

    case req @ POST -> Root / "hx" / "logout" =>
      userService.signOut(req.authToken) >>
        HttpResponse.redirect("logged out", "/", req).map(_.addCookie(ResponseCookie(
          "token",
          "",
          path = Some("/"),
          secure = true,
          httpOnly = true,
          sameSite = Some(SameSite.Lax),
          expires = Some(HttpDate.MinValue),
        )))

    case GET -> Root / "signup" =>
      val body =
        form(
          cls := "login-page",
          LogoHeader(),
          div (
            cls := "login-inputs",
            input(`type` := "text", name := "username", id := "text", placeholder := "User name", required),
            input(`type` := "text", name := "email", id := "email", placeholder := "Email", required),
            input(`type` := "password", name := "password", id := "password", placeholder := "Password", required),
            input(`type` := "password", name := "password2", id := "password2", placeholder := "Repeat Password", required),
          ),
          button(hxPost := "/hx/signup", hxDisableThis, "Sign Up"),
          div(
            cls := "login-hint",
            small("Already have an account? Click ", a(href := "/login")("here"), " to login."),
            small("By signing up, you agree to the ",
              a(href := "https://www.rssbrain.com/terms", target := "_blank")("Terms and Conditions"), " and ",
              a(href := "https://www.rssbrain.com/privacy", target := "_blank")("Privacy Policy"), "."),
          ),
        )
      Ok(Html(body), `Content-Type`(MediaType.text.html))


    case req @ POST -> Root / "hx" / "signup" =>
      req.decode[UrlForm] { data =>
        val email = data.values.get("email").flatMap(_.headOption).get
        val password = data.values.get("password").flatMap(_.headOption).get
        val password2 = data.values.get("password2").flatMap(_.headOption).get
        if (password != password2) {
          IO.raiseError(new Exception("Password doesn't match"))
        } else {
          userService.signUp(
            data.values.get("username").flatMap(_.headOption).get,
            password,
            email
          ).flatMap { _ =>
            HttpResponse.redirect("active", s"/waiting-active/$email", req)
          }
        }
      }

    case GET -> Root / "waiting-active" / email =>
      val dom = form(
        cls := "login-page",
        LogoHeader(),
        div(s"User created successfully. We have sent you an Email to $email with instructions to active the account."),
      )
      Ok(Html(dom), `Content-Type`(MediaType.text.html))


    case req @ GET -> Root / "active" =>
      val code = req.params.getOrElse("code", "")
      userService.activeAccount(code).map { _ =>
        div(s"Account activated successful. Click ",
          a(href := "/login", "here"),
          " to login",
        )
      }.handleError{ err =>
        div(
          p(s"Account active failed. Reason: ${err.getMessage}."),
          p(s"This may happen if you have already used this code to active the account. " +
            s"If you believe it's the reason, click ",
            a(href := "/login", "here"), " to try login.",
          ),
          p("If you still have any question, feel free to contact us at ",
            a(href := "mailto:customer-service@rssbrain.com", "customer-service@rssbrain.com"),
            ".",
          ),
        )
      }.flatMap { msg =>
        val dom = div(
          cls := "login-page",
          LogoHeader(),
          msg
        )
        Ok(Html(dom), `Content-Type`(MediaType.text.html))
      }

    case GET -> Root / "reset-password-request" =>
      val dom = form(
        action := "/reset-password-request",
        method := "POST",
        cls := "login-page",
        LogoHeader(),
        div(
          cls := "login-inputs",
          label("Input your Email address. We will send you the instructions to reset the password."),
          input(`type` := "text", name := "email", id := "email", placeholder := "Email", required),
        ),
        button("Reset Password"),
      )
      Ok(Html(dom), `Content-Type`(MediaType.text.html))


    case req @ POST -> Root / "reset-password-request" =>
      req.decode[UrlForm] { data =>
        val email = data.values.get("email").flatMap(_.headOption).get
        userService.requestResetPassword(email)
          .map(_ => div(s"We have sent you an Email to $email. Please follow the instructions to reset your password. "))
          .handleError(err => div(s"Error to request password reset. Reason: ${err.getMessage}"))
          .flatMap { msg =>
            val dom = div(
              cls := "login-page",
              LogoHeader(),
              msg
            )
            Ok(Html(dom), `Content-Type`(MediaType.text.html))
          }
      }


    case GET -> Root / "reset-password" / code =>
      val dom = form(
        cls := "login-page",
        action := "/reset-password",
        method := "POST",
        LogoHeader(),
        div(
          cls := "login-inputs",
          label("Input your new password below:"),
          input(`type` := "password", name := "password", id := "password", placeholder := "New Password",
            required),
          input(`type` := "password", name := "password2", id := "password2", placeholder := "Repeat New Password",
            required),
          input(`type` := "hidden", name := "code", value := code)
        ),
        button("Reset Password"),
      )
      Ok(Html(dom), `Content-Type`(MediaType.text.html))


    case req @ POST -> Root / "reset-password" =>
      req.decode[UrlForm] { data =>
        val code = data.values.get("code").flatMap(_.headOption).get
        val password = data.values.get("password").flatMap(_.headOption).get
        val password2 = data.values.get("password2").flatMap(_.headOption).get
        if (password != password2) {
           IO.raiseError(new Exception("Password doesn't match"))
        } else {
          userService.resetPassword(code, password).map { _ =>
            div("Password reset successful. Click ",
              a(href := "/login", "here"),
              " to login",
            )
          }
        }.handleError { err =>
          div(s"Reset password failed. Reason: ${err.getMessage}")
        }.flatMap { msg =>
          val dom = div(
            cls := "login-page",
            LogoHeader(),
            msg,
          )
          Ok(Html(dom), `Content-Type`(MediaType.text.html))
        }
      }

  }

}
