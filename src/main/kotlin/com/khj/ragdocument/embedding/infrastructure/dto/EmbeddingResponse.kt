package com.khj.ragdocument.embedding.infrastructure.dto

data class EmbeddingResponse(
    val data: List<EmbeddingData>,
)

data class EmbeddingData(
    val index: Int,
    val embedding: List<Float>,
)
