package me.binwang.rss.llm

import cats.effect.IO
import me.binwang.rss.llm.LargeLanguageModel.initMessage
import me.binwang.rss.model.Article
import org.typelevel.log4cats.LoggerFactory

case class ChatMessage(
    role: String,
    content: String,
)

object LargeLanguageModel {
  val initMessage: ChatMessage = ChatMessage(
    role = "system",
    content = "You are a helpful assistant.",
  )
}

trait LargeLanguageModel {

  implicit def loggerFactory: LoggerFactory[IO]
  private val logger = LoggerFactory.getLoggerFromClass[IO](this.getClass)

  // return multiple choices based on previous message
  def chat(messages: Seq[ChatMessage], apiKey: String): IO[Seq[ChatMessage]]

  def getRecommendSearchQueries(articles: Seq[Article], size: Int, apiKey: String): IO[Seq[String]] = {
    val interestQueryStr =
      """Here are some recent posts in a user's subscription feed, what is his interest?
        | Which languages the user speak?""".stripMargin + "\n\n" +
        articles.map(a => s"* ${a.title}, posted at ${a.postedAt.toLocalDateTime}").mkString("\n")
    val interestQuery = ChatMessage(role = "user", content = interestQueryStr)
    val initReq = Seq(initMessage, interestQuery)
    for {
      interestChoices <- chat(initReq, apiKey)
      recommendQueryStr = s"""Based on that, could you help me to come with $size search queries so that I can find more
        | interesting articles or videos for him/her on the Internet? Better to use all the languages the user speaks.
        | Show only the search queries without any explanation. One query per line. Do not include number or quotes""".stripMargin
      recommendQuery = ChatMessage(role = "user", content = recommendQueryStr)
      nextQueries = initReq :+ interestChoices.head :+ recommendQuery
      recommendChoices <- chat(nextQueries, apiKey)
      // TODO: change to debug (or output to a seperate log file) after tuning results
      _ <- logger.info(s"LLM prompts and results for recommend search. Query: $nextQueries, result: $recommendChoices")
      result = recommendChoices.head.content.split('\n').map(removeLeadingListNumber).filter(_.nonEmpty)
    } yield result
  }

  private def removeLeadingListNumber(s: String): String = {
    if (s.length > 2 && s.charAt(0) >= '0' && s.charAt(0) <= '9' && s.charAt(1) == '.') {
      s.substring(2)
    } else if (s.length > 1 && (s.charAt(0) == '*' || s.charAt(0) == '-')) {
      s.substring(1)
    } else {
      s
    }
  }

}
