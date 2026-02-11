package com.khj.ragdocument.rag.application

import com.khj.ragdocument.document.domain.ChunkRepository
import com.khj.ragdocument.document.domain.DocumentRepository
import com.khj.ragdocument.embedding.domain.EmbeddingClient
import com.khj.ragdocument.rag.domain.*
import com.khj.ragdocument.vectorstore.domain.VectorStoreRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class RagFacade(
    private val embeddingClient: EmbeddingClient,
    private val vectorStoreRepository: VectorStoreRepository,
    private val chunkRepository: ChunkRepository,
    private val documentRepository: DocumentRepository,
    private val chatClient: ChatClient,
) {

    fun ask(question: String, topK: Int = 5): Answer {
        // 1. 질문 임베딩
        val queryEmbedding = embeddingClient.embed(question)

        // 2. Milvus에서 유사 청크 검색
        val vectorResults = vectorStoreRepository.search(queryEmbedding, topK)

        // 3. 청크 내용 조회
        val chunkIds = vectorResults.map { it.chunkId }
        val chunks = chunkRepository.findAllById(chunkIds)
        val chunkMap = chunks.associateBy { it.id }

        // 4. 검색 결과 조합
        val searchResults = vectorResults.mapNotNull { result ->
            val chunk = chunkMap[result.chunkId] ?: return@mapNotNull null
            SearchResult(
                chunkId = chunk.id,
                content = chunk.content,
                score = result.score,
                documentId = chunk.documentId,
                chunkIndex = chunk.chunkIndex,
            )
        }

        // 5. 프롬프트 구성
        val prompt = buildPrompt(question, searchResults)

        // 6. GPT 답변 생성
        val answerContent = chatClient.generate(prompt)

        // 7. 출처 정보 구성
        val sources = searchResults.map { result ->
            val document = documentRepository.findById(result.documentId).orElse(null)
            AnswerSource(
                documentId = result.documentId,
                documentTitle = document?.title ?: "알 수 없음",
                chunkIndex = result.chunkIndex,
                content = result.content,
                score = result.score,
            )
        }

        return Answer(content = answerContent, sources = sources)
    }

    private fun buildPrompt(question: String, searchResults: List<SearchResult>): String {
        val context = searchResults.mapIndexed { index, result ->
            "[문서 ${index + 1}] (유사도: ${result.score})\n${result.content}"
        }.joinToString("\n\n")

        return """
            아래 문서 내용을 참고하여 질문에 답변하세요.

            === 참고 문서 ===
            $context

            === 질문 ===
            $question

            === 답변 형식 ===
            답변 내용을 작성하고, 참고한 문서 번호를 [문서 N] 형식으로 표시하세요.
        """.trimIndent()
    }
}
