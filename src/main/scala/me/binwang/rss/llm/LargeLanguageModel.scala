package me.binwang.rss.llm

import cats.effect.IO
import me.binwang.rss.llm.LargeLanguageModel.initMessage
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
  def chat(messages: Seq[ChatMessage]): IO[Seq[ChatMessage]]

  def getRecommendSearchQueries(likedArticleTitles: Seq[String], size: Int): IO[Seq[String]] = {
    // TODO: replace "videos" in prompt based on the type of articles
    val interestQueryStr =
      """Here are some recent videos in a user's subscription feed, what is his interest?
        | Which languages the user speak?""".stripMargin + "\n\n" +
        likedArticleTitles.map(t => s"* $t").mkString("\n")
    val interestQuery = ChatMessage(role = "user", content = interestQueryStr)
    val initReq = Seq(initMessage, interestQuery)
    for {
      interestChoices <- chat(initReq)
      recommendQueryStr = s"""Based on that, could you help me to come with $size search queries so that I can find more
        | interesting videos for him/her on the Internet? Better to use all the languages the user speaks.
        | Show only the search queries without any explanation. One query per line.""".stripMargin
      recommendQuery = ChatMessage(role = "user", content = recommendQueryStr)
      nextQueries = initReq :+ interestChoices.head :+ recommendQuery
      recommendChoices <- chat(nextQueries)
      // TODO: change to debug (or output to a seperate log file) after tuning results
      _ <- logger.info(s"LLM prompts and results for recommend search. Query: $nextQueries, result: $recommendChoices")
      result = recommendChoices.head.content.split('\n').map(removeLeadingListNumber)
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
