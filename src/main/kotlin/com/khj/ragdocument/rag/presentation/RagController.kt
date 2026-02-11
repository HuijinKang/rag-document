package com.khj.ragdocument.rag.presentation

import com.khj.ragdocument.rag.application.RagFacade
import com.khj.ragdocument.rag.presentation.dto.AskRequest
import com.khj.ragdocument.rag.presentation.dto.AskResponse
import com.khj.ragdocument.rag.presentation.dto.SourceResponse
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/rag")
class RagController(
    private val ragFacade: RagFacade,
) {
    @PostMapping("/ask")
    fun ask(@RequestBody request: AskRequest): AskResponse {
        val answer = ragFacade.ask(
            question = request.question,
            topK = request.topK ?: 5,
        )
        return AskResponse(
            answer = answer.content,
            sources = answer.sources.map {
                SourceResponse(
                    documentTitle = it.documentTitle,
                    chunkIndex = it.chunkIndex,
                    content = it.content,
                    score = it.score,
                )
            },
        )
    }
}
