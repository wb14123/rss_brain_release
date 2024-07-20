package me.binwang.rss.web

import cats.effect._
import me.binwang.rss.service.FolderService
import org.http4s.{HttpRoutes, _}
import org.http4s.dsl.io._
import org.http4s.headers._


class ExportWebService(folderService: FolderService)  {

  object tokenParam extends QueryParamDecoderMatcher[String]("token")
  val route: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "exportOPML" :? tokenParam(token) =>
      Ok(folderService.exportOPML(token))
        .map(_.withHeaders(
          `Content-Type`(MediaType.text.xml),
          "X-Content-Type-Options" -> "nosniff"
        ))
  }

}
