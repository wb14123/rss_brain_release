package me.binwang.rss.llm
import cats.effect.IO
import com.typesafe.config.ConfigFactory
import sttp.client3._
import io.circe.generic.auto._
import org.typelevel.log4cats.LoggerFactory
import sttp.client3.circe._

case class OpenAIChatRequest(
    model: String,
    messages: Seq[ChatMessage],
)

case class OpenAIChoices(
    index: Int,
    message: ChatMessage
)

case class OpenAIChatResponse(
    choices: Seq[OpenAIChoices]
)

class OpenAILLM(backend: SttpBackend[IO, _])(implicit val loggerFactory: LoggerFactory[IO]) extends LargeLanguageModel {

  private val model = ConfigFactory.load().getString("open-ai.model")

  override def chat(messages: Seq[ChatMessage], apiKey: String): IO[Seq[ChatMessage]] = {
    val req = OpenAIChatRequest(model, messages)
    basicRequest
      .post(uri"https://api.openai.com/v1/chat/completions")
      .header("Authorization", s"Bearer $apiKey")
      .body(req)
      .response(asJson[OpenAIChatResponse])
      .send(backend)
      .map(_.body.toTry.map(_.choices.map(_.message)).get)
  }
}
