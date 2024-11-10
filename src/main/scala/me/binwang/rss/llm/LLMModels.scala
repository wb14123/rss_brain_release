package me.binwang.rss.llm

import me.binwang.rss.model.LLMEngine
import me.binwang.rss.model.LLMEngine.LLMEngine

case class LLMModels(
  openAI: OpenAILLM,
) {
  def getModel(llmEngine: LLMEngine): LargeLanguageModel = llmEngine match {
    case LLMEngine.OpenAI => openAI
  }
}
