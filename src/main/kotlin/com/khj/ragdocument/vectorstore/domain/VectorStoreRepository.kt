package com.khj.ragdocument.vectorstore.domain

interface VectorStoreRepository {
    fun save(chunkId: Long, embedding: List<Float>)
    fun saveBatch(chunkEmbeddings: Map<Long, List<Float>>)
    fun search(queryEmbedding: List<Float>, topK: Int = 5): List<VectorSearchResult>
}

data class VectorSearchResult(
    val chunkId: Long,
    val score: Float,
)
