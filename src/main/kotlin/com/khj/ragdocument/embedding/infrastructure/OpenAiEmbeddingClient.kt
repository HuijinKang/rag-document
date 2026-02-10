package com.khj.ragdocument.embedding.infrastructure

import com.khj.ragdocument.embedding.domain.EmbeddingClient
import com.khj.ragdocument.embedding.infrastructure.dto.EmbeddingResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient

@Component
class OpenAiEmbeddingClient(
    private val webClient: WebClient,
    @Value("\${openai.api-key}") private val apiKey: String,
    @Value("\${openai.embedding-model}") private val model: String,
) : EmbeddingClient {

    override fun embed(text: String): List<Float> {
        return embedBatch(listOf(text)).first()
    }

    override fun embedBatch(texts: List<String>): List<List<Float>> {
        val response = webClient.post()
            .uri("https://api.openai.com/v1/embeddings")
            .header("Authorization", "Bearer $apiKey")
            .bodyValue(
                mapOf(
                    "model" to model,
                    "input" to texts,
                )
            )
            .retrieve()
            .bodyToMono(EmbeddingResponse::class.java)
            .block() ?: throw RuntimeException("임베딩 API 호출 실패")

        return response.data
            .sortedBy { it.index }
            .map { it.embedding }
    }
}
