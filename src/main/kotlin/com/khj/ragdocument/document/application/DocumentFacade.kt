package com.khj.ragdocument.document.application

import com.khj.ragdocument.document.domain.*
import com.khj.ragdocument.embedding.domain.EmbeddingClient
import com.khj.ragdocument.vectorstore.domain.VectorStoreRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class DocumentFacade(
    private val documentRepository: DocumentRepository,
    private val chunkRepository: ChunkRepository,
    private val embeddingClient: EmbeddingClient,
    private val vectorStoreRepository: VectorStoreRepository,
) {
    private val chunkingStrategy = ChunkingStrategy()

    fun ingest(title: String, content: String, sourceType: SourceType): Long {
        // 1. Document 저장
        val document = documentRepository.save(
            Document(title = title, content = content, sourceType = sourceType)
        )

        // 2. 청크 분할
        val chunks = document.splitToChunks(chunkingStrategy)
        val savedChunks = chunkRepository.saveAll(chunks)

        // 3. 임베딩 생성
        val texts = savedChunks.map { it.content }
        val embeddings = embeddingClient.embedBatch(texts)

        // 4. Milvus에 벡터 저장
        val chunkEmbeddings = savedChunks.zip(embeddings)
            .associate { (chunk, embedding) -> chunk.id to embedding }
        vectorStoreRepository.saveBatch(chunkEmbeddings)

        return document.id
    }
}
