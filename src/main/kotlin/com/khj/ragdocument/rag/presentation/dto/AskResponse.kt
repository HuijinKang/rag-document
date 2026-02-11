package com.khj.ragdocument.rag.presentation.dto

data class AskResponse(
    val answer: String,
    val sources: List<SourceResponse>,
)

data class SourceResponse(
    val documentTitle: String,
    val chunkIndex: Int,
    val content: String,
    val score: Float,
)
