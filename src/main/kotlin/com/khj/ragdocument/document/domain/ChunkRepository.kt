package com.khj.ragdocument.document.domain

import org.springframework.data.jpa.repository.JpaRepository

interface ChunkRepository : JpaRepository<Chunk, Long> {
    fun findByDocumentId(documentId: Long): List<Chunk>
}
