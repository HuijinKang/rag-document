package com.khj.ragdocument.rag.domain

data class Answer(
    val content: String,
    val sources: List<AnswerSource>,
)

data class AnswerSource(
    val documentId: Long,
    val documentTitle: String,
    val chunkIndex: Int,
    val content: String,
    val score: Float,
)
