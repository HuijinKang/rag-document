package com.khj.ragdocument.rag.infrastructure.dto

data class ChatResponse(
    val choices: List<ChatChoice>,
)

data class ChatChoice(
    val message: ChatMessage,
)

data class ChatMessage(
    val role: String,
    val content: String,
)
