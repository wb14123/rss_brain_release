package me.binwang.rss.webview.widgets

import cats.effect.IO
import me.binwang.rss.model.SearchOptions
import me.binwang.rss.webview.basic.ContentRender.hxSwapContentAttrs
import me.binwang.rss.webview.basic.ScalaTagAttributes.{hxGet, hxInclude, hxPushUrl}
import me.binwang.rss.webview.routes.ArticleList.mapToQueryStr
import me.binwang.rss.webview.widgets.TimeSelection._
import org.http4s.Request
import scalatags.Text.all._

object SearchBox {

  def apply(baseLink: String, req: Request[IO], searchOptions: Option[SearchOptions] = None): Frag = {
    val newParams = req.params --
      Seq("noWrap", "q", "time_range", "by_time") ++
      Map("showSearchBox" -> "", "highlight" -> "")

    val timeRangeOpt = req.params.get("time_range")
    form(
      id := "search-area",
      div(
        cls := "form-row search-box-row",
        input(`type` := "text", name := "q", id := "search-box", searchOptions.map(s => value := s.query)),
        button(hxSwapContentAttrs, hxInclude := "#search-area input, #search-area select", hxPushUrl := "true",
          hxGet := s"$baseLink?${mapToQueryStr(newParams)}")("Search"),
      ),
      div(
        cls := "form-row search-options",
        label("Order By"),
        select(
          name := "by_time",
          option(value := "false", "Most relevant first", if (!searchOptions.exists(_.sortByTime)) selected else ""),
          option(value := "true", "Most recent first", if (searchOptions.exists(_.sortByTime)) selected else ""),
        ),
        label("Time"),
        select(
          name := "time_range",
          option(value := "", "All time"),
          option(value := daySeconds, "Past 24 hours", timeSelected(daySeconds, timeRangeOpt)),
          option(value := weekSeconds, "Past week", timeSelected(weekSeconds, timeRangeOpt)),
          option(value := monthSeconds, "Past month", timeSelected(monthSeconds, timeRangeOpt)),
          option(value := yearSeconds, "Past year", timeSelected(yearSeconds, timeRangeOpt)),
        )
      ),
    )
  }

}
