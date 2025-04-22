package me.binwang.rss.model

import me.binwang.rss.model.LLMEngine.LLMEngine

import java.time.ZonedDateTime
import java.util.UUID
import me.binwang.rss.model.NSFWSetting.NSFWSetting

/**
 * How to show NSFW content. This is only used for client to display the content. Server does nothing other than
 * storing the value for the clients. Clients can decide what to do based on the values but here is the guide:
 */
object NSFWSetting extends Enumeration {
  type NSFWSetting = Value

  /**
   * Hide the NSFW content in article list. But can be shown when click into article.
   */
  val HIDE: NSFWSetting = Value

  /**
   * Blur the NSFW images and video cover. Hide the text in article list. But all the content will be shown when
   * click into the article.
   */
  val BLUR: NSFWSetting = Value

  /**
   * Show all the NSFW content in the article list.
   */
  val SHOW: NSFWSetting = Value
}

object LLMEngine extends Enumeration {
  type LLMEngine = Value
  val
  OpenAI
  = Value
}

case class SearchEngine(name: Option[String], urlPrefix: String)

object SearchEngine {
  val DUCKDUCKGO = SearchEngine(Some("DuckDuckGo"), "https://duckduckgo.com/?q=")
  val GOOGLE = SearchEngine(Some("Google"), "https://www.google.com/search?q=")
  val BING = SearchEngine(Some("Bing"), "https://www.bing.com/search?q=")
  val KAGI = SearchEngine(Some("Kagi"), "https://kagi.com/search?q=")

  val ALL = Seq(DUCKDUCKGO, GOOGLE, BING, KAGI)
  val DEFAULT = DUCKDUCKGO
}

case class User (
  id: String,
  username: String,
  password: String,
  salt: String,
  email: String,
  defaultFolderID: String,
  lastLoginTime: Option[ZonedDateTime],
  lastLoginIP: Option[String],
  isActive: Boolean,
  isAdmin: Boolean,
  createdAt: ZonedDateTime,
  currentFolderID: Option[String] = None,
  currentSourceID: Option[String] = None,
  activeCode: Option[String] = Some(UUID.randomUUID().toString),
  subscribeEndAt: ZonedDateTime,
  subscribed: Boolean = false,
  nsfwSetting: NSFWSetting = NSFWSetting.BLUR,
  searchEngine: SearchEngine = SearchEngine.DEFAULT,
  llmEngine: Option[LLMEngine] = None,
  llmApiKey: Option[String] = None,
) {

  def toInfo: UserInfo = {
    UserInfo(
      id = id,
      username = username,
      email = email,
      createdAt = createdAt,
      defaultFolderID = defaultFolderID,
      currentFolderID = currentFolderID,
      currentSourceID = currentSourceID,
      isAdmin = isAdmin,
      subscribeEndAt = subscribeEndAt,
      subscribed = subscribed,
      nsfwSetting = nsfwSetting,
      searchEngine = searchEngine,
      llmEngine = llmEngine,
    )
  }

}

/**
 * The user information returned to client. Removed sensitive information like password and activeCode.
 *
 * @param currentFolderID The last folder that the user visited. None if user last visited a source instead of folder.
 * @param currentSourceID The last source that the user visited. None if user last visited a folder instead of source.
 */
case class UserInfo (
  id: String,
  username: String,
  email: String,
  createdAt: ZonedDateTime,
  defaultFolderID: String,
  isAdmin: Boolean,
  subscribeEndAt: ZonedDateTime,
  currentFolderID: Option[String] = None,
  currentSourceID: Option[String] = None,
  subscribed: Boolean = false,
  nsfwSetting: NSFWSetting = NSFWSetting.BLUR,
  searchEngine: SearchEngine = SearchEngine.DEFAULT,
  llmEngine: Option[LLMEngine] = None,
)

/**
 * Structure to update user information. Every param is default to None which means no update for that field.
 */
case class UserUpdater (
  password: Option[String] = None,
  salt: Option[String] = None,
  email: Option[String] = None,
  lastLoginTime: Option[Option[ZonedDateTime]] = None,
  lastLoginIP: Option[Option[String]] = None,
  isActive: Option[Boolean] = None,
  isAdmin: Option[Boolean] = None,
  currentFolderID: Option[Option[String]] = None,
  currentSourceID: Option[Option[String]] = None,
  activeCode: Option[Option[String]] = None,
  subscribeEndAt: Option[ZonedDateTime] = None,
  subscribed: Option[Boolean] = None,
  username: Option[String] = None,
  nsfwSetting: Option[NSFWSetting] = None,
  searchEngine: Option[SearchEngine] = None,
  llmEngine: Option[Option[LLMEngine]] = None,
  llmApiKey: Option[Option[String]] = None,
)
