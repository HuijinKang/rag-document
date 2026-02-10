package com.khj.ragdocument.document.domain

import jakarta.persistence.*

@Entity
@Table(name = "chunks")
class Chunk(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    val documentId: Long,

    @Column(columnDefinition = "TEXT")
    val content: String,

    val chunkIndex: Int,

    val tokenCount: Int,
)
