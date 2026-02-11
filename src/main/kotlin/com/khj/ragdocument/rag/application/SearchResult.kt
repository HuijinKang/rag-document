package com.khj.ragdocument.rag.application

data class SearchResult(
    val chunkId: Long,
    val content: String,
    val score: Float,
    val documentId: Long,
    val chunkIndex: Int,
)
