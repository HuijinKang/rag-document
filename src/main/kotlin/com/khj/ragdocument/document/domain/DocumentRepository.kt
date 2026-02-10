package com.khj.ragdocument.document.domain

import org.springframework.data.jpa.repository.JpaRepository

interface DocumentRepository : JpaRepository<Document, Long>
