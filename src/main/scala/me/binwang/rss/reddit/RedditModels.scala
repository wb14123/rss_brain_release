package me.binwang.rss.reddit

import io.circe.generic.extras._

object RedditModels {

  implicit val config: Configuration = Configuration.default.withSnakeCaseMemberNames

  @ConfiguredJsonCodec case class RedditToken(
      accessToken: String,
      expiresIn: Int,
      scope: String,
      refreshToken: String,
  )

  @ConfiguredJsonCodec case class RedditUser(id: String, name: String)

  @ConfiguredJsonCodec case class RedditPost(
      id: String,
      name: String,
      title: String,
      ups: Int,
      downs: Int,
      numComments: Int,
      selftext: Option[String],
      selftextHtml: Option[String],
      url: Option[String],
      permalink: String,
      author: String,
      thumbnail: String,
      subreddit: String,
      subredditId: String,
      created: Long,
      over_18: Boolean,
      postHint: Option[String],
      preview: Option[RedditPreview],
      media: Option[RedditMedia],
      galleryData: Option[RedditGalleryData],
      mediaMetadata: Option[Map[String, RedditMediaMetadata]],
  )

  @ConfiguredJsonCodec case class RedditImage(
      source: RedditImageContent,
      resolutions: Seq[RedditImageContent],
  )

  @ConfiguredJsonCodec case class RedditImageContent(
      url: String,
      width: Int,
      height: Int,
  )

  @ConfiguredJsonCodec case class RedditPreview(
      images: Option[Seq[RedditImage]]
  )

  @ConfiguredJsonCodec case class RedditMedia(
      redditVideo: Option[RedditVideo]
  )

  @ConfiguredJsonCodec case class RedditVideo(
      fallbackUrl: String,
      width: Int,
      height: Int,
  )

  @ConfiguredJsonCodec case class RedditGalleryData(
      items: Seq[RedditGalleryItem]
  )

  @ConfiguredJsonCodec case class RedditGalleryItem(
      mediaId: String,
  )

  @ConfiguredJsonCodec case class RedditMediaMetadata(
      id: Option[String],
      s: Option[RedditMediaMetadataSource],
      e: Option[String],
      m: Option[String],
  )

  @ConfiguredJsonCodec case class RedditMediaMetadataSource(
      x: Int,
      y: Int,
      u: Option[String],
  )

  @ConfiguredJsonCodec case class RedditPostData(data: RedditPost)

  @ConfiguredJsonCodec case class RedditPostListData(
      after: String,
      children: Seq[RedditPostData]
  )

  @ConfiguredJsonCodec case class RedditPostList(data: RedditPostListData)

  @ConfiguredJsonCodec case class SubRedditResponse(data: SubReddit)

  @ConfiguredJsonCodec case class SubReddit(
      title: String,
      publicDescription: String,
      url: String,
      iconImg: Option[String],
  )
}
