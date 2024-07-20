package me.binwang.rss.model

case class MediaGroups(
    groups: Seq[MediaGroup]
)

case class MediaGroup(
    content: MediaContent,
    title: Option[String] = None,
    keyword: Option[String] = None,
    description: Option[String] = None,
    hash: Option[String] = None,
    rating: Option[MediaRating] = None,
    thumbnails: Seq[MediaThumbnail] = Seq(),
    text: Seq[MediaText] = Seq(),
    comments: Seq[String] = Seq(),
)

object MediaMedium {
  val IMAGE = "image"
  val AUDIO = "audio"
  val VIDEO = "video"
  val DOCUMENT = "document"
  val EXECUTABLE = "executable"
}

case class MediaContent(
    url: String,
    fileSize: Option[Long] = None,
    typ: Option[String] = None,
    medium: Option[String] = None, // image | audio | video | document | executable
    isDefault: Boolean = false,
    expression: String = "full", // sample | full | nonstop
    height: Option[Int] = None,
    width: Option[Int] = None,
    lang: Option[String] = None,
    fromArticle: Option[Boolean] = Some(false), // parsed from article content itself instead of media tags
)

case class MediaRating(
    value: String,
    scheme: Option[String],
)

case class MediaThumbnail(
    url: String,
    width: Option[Int] = None,
    height: Option[Int] = None,
    time: Option[String] = None,
)

case class MediaPlayer(
    url: String,
    height: Option[Int] = None,
    width: Option[Int] = None,
)

case class MediaText(
    lang: Option[String] = None,
    start: Option[String] = None,
    end: Option[String] = None,
)