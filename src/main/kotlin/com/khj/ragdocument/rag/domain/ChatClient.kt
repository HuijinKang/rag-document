package com.khj.ragdocument.rag.domain

interface ChatClient {
    fun generate(prompt: String): String
}
