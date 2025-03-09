package me.binwang.rss.webview.widgets

import me.binwang.rss.model.ArticleWithUserMarking
import me.binwang.rss.webview.basic.ScalaTagAttributes.is
import scalatags.Text.all._

object YoutubeVideoPlayer {

  // regex from https://stackoverflow.com/questions/3452546/how-do-i-get-the-youtube-video-id-from-a-url
  private val regExp = """^.*((youtu.be\/)|(v\/)|(\/u\/\w\/)|(embed\/)|(watch\?))\??v?=?([^#&?]*).*""".r

  private def getVideoID(urlStr: String): Option[String] = {
    regExp.findFirstMatchIn(urlStr).map(_.group(7))
  }

  def apply(article: ArticleWithUserMarking, videoUrl: String): Option[Frag] = {
    getVideoID(videoUrl).map { videoID =>
      div(
        cls := "video-player",
        tag("lite-youtube-tracker")(
          attr("videoid") := videoID,
          attr("js-api").empty,
          attr("saved-progress") := article.userMarking.readProgress,
          attr("save-progress-url") := SaveProgressUrl(article.article.id),
        ),
      )
    }
  }

}
