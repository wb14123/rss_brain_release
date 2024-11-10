package me.binwang.rss.model

import me.binwang.rss.model.LLMEngine.LLMEngine

import java.time.ZonedDateTime
import java.util.UUID
import me.binwang.rss.model.NSFWSetting.NSFWSetting

object NSFWSetting extends Enumeration {
  type NSFWSetting = Value
  val
  HIDE,
  BLUR,
  SHOW
  = Value
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
