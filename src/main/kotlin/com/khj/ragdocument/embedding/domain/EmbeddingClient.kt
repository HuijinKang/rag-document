package com.khj.ragdocument.embedding.domain

interface EmbeddingClient {
    fun embed(text: String): List<Float>
    fun embedBatch(texts: List<String>): List<List<Float>>
}
