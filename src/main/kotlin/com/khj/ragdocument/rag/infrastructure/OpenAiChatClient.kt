package com.khj.ragdocument.rag.infrastructure

import com.khj.ragdocument.rag.domain.ChatClient
import com.khj.ragdocument.rag.infrastructure.dto.ChatResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient

@Component
class OpenAiChatClient(
    private val webClient: WebClient,
    @Value("\${openai.api-key}") private val apiKey: String,
    @Value("\${openai.model}") private val model: String,
) : ChatClient {

    override fun generate(prompt: String): String {
        val response = webClient.post()
            .uri("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .bodyValue(
                mapOf(
                    "model" to model,
                    "messages" to listOf(
                        mapOf("role" to "system", "content" to SYSTEM_PROMPT),
                        mapOf("role" to "user", "content" to prompt),
                    ),
                    "temperature" to 0.3,
                )
            )
            .retrieve()
            .bodyToMono(ChatResponse::class.java)
            .block() ?: throw RuntimeException("Chat API 호출 실패")

        return response.choices.first().message.content
    }

    companion object {
        private const val SYSTEM_PROMPT =
            "당신은 문서 기반 질의응답 어시스턴트입니다. " +
            "제공된 문서 내용만을 기반으로 정확하게 답변하세요. " +
            "문서에 없는 내용은 \"제공된 문서에서 해당 정보를 찾을 수 없습니다.\"라고 답변하세요. " +
            "답변 시 어떤 문서의 어느 부분을 참고했는지 출처를 함께 제시하세요."
    }
}
