package me.binwang.rss.webview.widgets

import scalatags.Text.all.{script, _}

object YoutubeVideoPlayer {

  // regex from https://stackoverflow.com/questions/3452546/how-do-i-get-the-youtube-video-id-from-a-url
  private val regExp = """^.*((youtu.be\/)|(v\/)|(\/u\/\w\/)|(embed\/)|(watch\?))\??v?=?([^#&?]*).*""".r

  private def getVideoID(urlStr: String): Option[String] = {
    regExp.findFirstMatchIn(urlStr).map(_.group(7))
  }

  def apply(videoUrl: String): Option[Frag] = {
    getVideoID(videoUrl).map { videoID =>
      div(
        cls := "video-player",
        tag("lite-youtube")(attr("videoid") := videoID),
        script(src := "/static/lib/lite-yt-embed-0.3.2.js"),
      )
    }
  }

}
