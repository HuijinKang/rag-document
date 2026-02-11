package com.khj.ragdocument.rag.presentation.dto

data class AskRequest(
    val question: String,
    val topK: Int? = 5,
)
