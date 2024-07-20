package me.binwang.rss.webview.routes
import cats.effect.IO
import me.binwang.rss.service.UserService
import me.binwang.rss.webview.auth.CookieGetter.reqToCookieGetter
import me.binwang.rss.webview.basic.HttpResponse
import org.http4s.HttpRoutes
import org.http4s.dsl.io._

class RootView(userService: UserService) extends Http4sView {

  override val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case req @ GET -> Root =>
      val token = req.authToken
      userService.getMyUserInfo(token).flatMap { userInfo =>
        val link = if (userInfo.currentFolderID.isDefined) {
          s"/folders/${userInfo.currentFolderID.get}/articles"
        } else if (userInfo.currentSourceID.isDefined) {
          s"/sources/${userInfo.currentSourceID.get}/articles"
        } else {
          s"/folders/${userInfo.defaultFolderID}/articles"
        }
        HttpResponse.fullRedirect("", link)
      }

    case req @ GET -> Root / "folders" / "default" =>
      val token = req.authToken
      userService.getMyUserInfo(token).flatMap { userInfo =>
        val link = s"/folders/${userInfo.defaultFolderID}/articles"
        HttpResponse.fullRedirect("", link)
      }

  }

}
