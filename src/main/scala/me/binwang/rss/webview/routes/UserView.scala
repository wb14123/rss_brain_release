package me.binwang.rss.webview.routes
import cats.effect.IO
import me.binwang.rss.model.{LLMEngine, NSFWSetting, SearchEngine, UserSession}
import me.binwang.rss.service.{SystemService, UserService}
import me.binwang.rss.webview.auth.CookieGetter.reqToCookieGetter
import me.binwang.rss.webview.basic.ContentRender.wrapContentRaw
import me.binwang.rss.webview.basic.ScalaTagAttributes._
import me.binwang.rss.webview.basic.{ContentRender, HttpResponse, ScalatagsSeqInstances}
import me.binwang.rss.webview.widgets.PageHeader
import org.http4s.{HttpRoutes, MediaType, UrlForm}
import org.http4s.dsl.io._
import org.http4s.headers.`Content-Type`
import org.http4s.scalatags.ScalatagsInstances
import scalatags.Text.all.{a, button, _}

class UserView(userService: UserService, systemService: SystemService) extends Http4sView with ScalatagsInstances with ScalatagsSeqInstances {

  private val customerServiceEmail = "customer-service@rssbrain.com"

  private def apiTokenRow(session: UserSession, showFullToken: Boolean = false): Frag = {
    val tokenName = session.tokenName.getOrElse[String]("Unnamed")
    val displayToken = if (showFullToken) session.token else session.token
    val escapedName = java.net.URLEncoder.encode(tokenName, "UTF-8")
    div(cls := "api-token-row", id := s"api-token-$escapedName",
      strong(tokenName),
      code(displayToken),
      button(cls := "secondary outline",
        hxDelete := s"/hx/settings/api-tokens/$escapedName",
        hxTarget := s"#api-token-$escapedName", hxSwap := "outerHTML", hxDisableThis, "Revoke"),
    )
  }

  override val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "hx" / "user" / "currentFolder" / folderID =>
      val token = req.authToken
      userService.setCurrentFolder(token, folderID).flatMap(r => Ok(r))

    case req @ POST -> Root / "hx" / "user" / "currentSource" / sourceID =>
      val token = req.authToken
      userService.setCurrentSource(token, sourceID).flatMap(r => Ok(r))

    case req @ GET -> Root / "feedback" => wrapContentRaw(req) {
      val dom = Seq(
        PageHeader(Some("Feedback")),
        div(
          cls := "form-body",
          div(
            p(
              "If you encounter any bug, report it to ",
              a(href := "https://github.com/wb14123/rss_brain_release/issues", target := "_blank")("Github issue tracker"), ".",
            ),
            p(
              "If you have any feedback or question, feel free to contact us at ",
              a(href := s"mailto:$customerServiceEmail")(customerServiceEmail), ".",
            ),
          )
        )
      )
      Ok(dom, `Content-Type`(MediaType.text.html))
    }

    case req @ POST -> Root / "settings" =>
      val token = req.authToken
      req.decode[UrlForm] { data =>

        // `search-engine-url` is disabled when submit if there is a name, so find the engine from predefined ones.
        val searchEngineName = data.values.get("search-engine-name").flatMap(_.headOption).filter(_.nonEmpty)
        val searchEngine = SearchEngine.ALL.find(_.name.equals(searchEngineName)).getOrElse(
          SearchEngine(name = searchEngineName,
            urlPrefix = data.values.get("search-engine-url").flatMap(_.headOption).get)
        )

        val llmEngine = data.values.get("llm-engine").flatMap(_.headOption.filter(_.nonEmpty)).map(LLMEngine.withName)
        val llmApiKey = data.values.get("llm-api-key").flatMap(_.headOption.filter(_.nonEmpty))

        userService.updateUserSettings(token = token,
          nsfwSetting = data.values.get("nsfw-setting").map(s => NSFWSetting.withName(s.headOption.get)),
          searchEngine = Some(searchEngine), Some(llmEngine), llmApiKey.map(Some(_)),
        ).flatMap(_ => HttpResponse.redirect("", "/settings", req))
      }


    case req @ POST -> Root / "hx" / "settings" / "api-tokens" =>
      val token = req.authToken
      req.decode[UrlForm] { data =>
        val tokenName = data.values.get("token-name").flatMap(_.headOption).getOrElse("Unnamed")
        userService.createApiToken(token, tokenName).flatMap { apiSession =>
          Ok(Seq(
            // appended into #api-token-list via hxSwap=beforeend
            apiTokenRow(apiSession),
            // swapped out-of-band into the hint area above the list
            div(id := "api-token-created-hint", hxSwapOob := "innerHTML",
              p(ins("Token created. Copy it now — it won't be shown again:")),
              p(code(apiSession.token)),
            ),
          ), `Content-Type`(MediaType.text.html))
        }
      }

    case req @ DELETE -> Root / "hx" / "settings" / "api-tokens" / apiTokenName =>
      val token = req.authToken
      val decodedName = java.net.URLDecoder.decode(apiTokenName, "UTF-8")
      userService.deleteApiToken(token, decodedName).flatMap(_ => Ok(""))

    case req @ GET -> Root / "hx" / "settings" / "api-tokens" =>
      val token = req.authToken
      userService.listApiTokens(token).compile.toList.flatMap { tokens =>
        if (tokens.isEmpty) {
          Ok(div(cls := "api-token-empty", "No tokens created yet."), `Content-Type`(MediaType.text.html))
        } else {
          Ok(tokens.map(t => apiTokenRow(t)), `Content-Type`(MediaType.text.html))
        }
      }

    case req @ GET -> Root / "hx" / "settings" / "nsfw" =>
      val token = req.authToken
      userService.getMyUserInfo(token).flatMap { user =>
        Ok(user.nsfwSetting.toString, `Content-Type`(MediaType.text.plain))
      }


    case req @ GET -> Root / "settings" => wrapContentRaw(req) {
      val token = req.authToken
      for {
        user <- userService.getMyUserInfo(token)
        apiVersion <- systemService.getApiVersion()
        paymentEnabled <- systemService.checkPaymentEnabled()
        dom = Seq(
          PageHeader(Some("Settings")),
          div(
            cls := "form-body form-start",
            div(
              cls := "form-section",
              h2("Account"),
              div(s"Username: ${user.username}"),
              div(s"Email: ${user.email}"),
              div(s"Account created at ", span(xDate(user.createdAt))),
              div(cls := "button-row", button(cls := "secondary", hxDisableThis, hxPost := "/hx/logout", "Sign Out")),
            ),
            hr(),
            if (!paymentEnabled) "" else div(
              cls := "form-section",
              h2("Payment"),
              div("Subscription will end at ", span(xDate(user.subscribeEndAt))),
              a(target := "_blank", href := "/payment/stripe/checkout", "Subscribe to RSS Brain"),
              a(target := "_blank", href := "/payment/stripe/portal", "Manage Subscription"),
              hr(),
            ),
            form(
              xData :=s"""{
                searchEngineName: '${user.searchEngine.name.getOrElse("")}',
                searchEngineUrl: '${user.searchEngine.urlPrefix}',
                nsfwSetting: '${user.nsfwSetting.toString}',
                llmEngine:  '${user.llmEngine.map(_.toString).getOrElse("")}',
                llmApiKey:  '',
                }""",
              cls := "form-section",
              h2("System"),
              label("Dark Mode"),
              select(
                name := "dark-mode",
                xOn("change") := "setTheme($el.value)",
                option("Follow System Settings", xBind("selected") := "getCurrentTheme() == 'auto'", value := "auto"),
                option("Disable", xBind("selected") := "getCurrentTheme() == 'light'", value := "light"),
                option("Enable", xBind("selected") := "getCurrentTheme() == 'dark'", value := "dark"),
              ),
              label("Search Engine"),
              select(
                name := "search-engine-name",
                xModel := "searchEngineName",
                xOn("change") := "searchEngineUrl = $el.options[$el.selectedIndex].dataset.url",
                (SearchEngine.ALL :+ user.searchEngine.copy(name=None)).map { searchEngine =>
                  option(
                    searchEngine.name.getOrElse[String]("Custom"),
                    value := searchEngine.name.getOrElse(""),
                    data("url") := searchEngine.urlPrefix,
                  )
                }
              ),
              label("Search Engine URL Prefix"),
              input(name := "search-engine-url", xModel := "searchEngineUrl", `type` := "text",
                xBind("disabled") := "searchEngineName !== ''"),
              label("LLM Engine"),
              select(
                name := "llm-engine",
                xModel := "llmEngine",
                option("Disable", value := ""),
                option("OpenAI", value := "OpenAI"),
              ),
              label("LLM API Key (Hidden once saved)"),
              input(name := "llm-api-key", xModel := "llmApiKey", `type` := "text",
                xBind("disabled") := "llmEngine == ''"),
              label("NSFW Content Display"),
              select(
                name := "nsfw-setting",
                xModel := "nsfwSetting",
                option("Hide", value := "HIDE"),
                option("Blur", value := "BLUR"),
                option("Show", value := "SHOW"),
              ),
              a(href := "/opml-export", target := "_blank", "Export OPML"),
              div(cls := "button-row",
                button(hxPost := "/settings", ContentRender.hxSwapContentAttrs, hxDisableThis, "Save")),
            ),
            hr(),
            div(
              cls := "form-section",
              h2("API Tokens"),
              p("Create read-only API tokens for third-party apps or scripts. These tokens can only read data (list articles, folders, etc.) and cannot make any changes."),
              label("Token Name"),
              input(name := "token-name", `type` := "text", placeholder := "Enter a name for the token"),
              div(cls := "button-row",
                button(hxPost := "/hx/settings/api-tokens",
                  hxTarget := "#api-token-list", hxSwap := "beforeend",
                  hxInclude := "[name='token-name']", hxDisableThis, "Create Token")),
              div(id := "api-token-created-hint"),
              label("Your Tokens"),
              div(id := "api-token-list", hxGet := "/hx/settings/api-tokens",
                hxTrigger := "load", hxSwap := "innerHTML"),
            ),
            hr(),
            div(
              cls := "form-section",
              h2("About"),
              div(s"RSS Brain version $apiVersion."),
              a(href := "https://www.rssbrain.com", target := "_blank", "Official Website"),
              a(href := "https://news.rssbrain.com", target := "_blank", "News"),
              a(href := "https://github.com/wb14123/rss_brain_release", target := "_blank", "Source Code"),
              a(href := s"mailto:$customerServiceEmail")(s"Send feed back to $customerServiceEmail"),
            )
          ),
        )
        result <- Ok(dom, `Content-Type`(MediaType.text.html))
      } yield result
    }

  }
}
