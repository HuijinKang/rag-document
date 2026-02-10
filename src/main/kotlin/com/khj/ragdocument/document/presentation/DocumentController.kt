package com.khj.ragdocument.document.presentation

import com.khj.ragdocument.document.application.DocumentFacade
import com.khj.ragdocument.document.domain.SourceType
import com.khj.ragdocument.document.presentation.dto.DocumentIngestRequest
import com.khj.ragdocument.document.presentation.dto.DocumentIngestResponse
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/documents")
class DocumentController(
    private val documentFacade: DocumentFacade,
) {
    @PostMapping
    fun ingest(@RequestBody request: DocumentIngestRequest): DocumentIngestResponse {
        val documentId = documentFacade.ingest(
            title = request.title,
            content = request.content,
            sourceType = SourceType.TEXT,
        )
        return DocumentIngestResponse(documentId = documentId, message = "문서 분석 완료")
    }
}
