package com.khj.ragdocument.document.domain

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "documents")
class Document(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    val title: String,

    @Column(columnDefinition = "TEXT")
    val content: String,

    @Enumerated(EnumType.STRING)
    val sourceType: SourceType,

    val sourceUrl: String? = null,

    val createdAt: LocalDateTime = LocalDateTime.now(),
) {
    fun splitToChunks(strategy: ChunkingStrategy): List<Chunk> {
        return strategy.split(this)
    }
}
