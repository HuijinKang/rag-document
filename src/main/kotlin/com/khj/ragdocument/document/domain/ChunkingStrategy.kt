package com.khj.ragdocument.document.domain

class ChunkingStrategy(
    private val maxChunkSize: Int = 500,
    private val overlap: Int = 50,
) {
    fun split(document: Document): List<Chunk> {
        val text = document.content
        val chunks = mutableListOf<Chunk>()
        var start = 0
        var index = 0

        while (start < text.length) {
            val end = minOf(start + maxChunkSize, text.length)
            val chunkText = text.substring(start, end)

            chunks.add(
                Chunk(
                    documentId = document.id,
                    content = chunkText,
                    chunkIndex = index,
                    tokenCount = chunkText.length / 4,
                )
            )
            start += maxChunkSize - overlap
            index++
        }

        return chunks
    }
}
