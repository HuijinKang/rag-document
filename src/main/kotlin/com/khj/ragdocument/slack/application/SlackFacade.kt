package com.khj.ragdocument.slack.application

import com.khj.ragdocument.document.application.DocumentFacade
import com.khj.ragdocument.document.domain.SourceType
import com.khj.ragdocument.rag.application.RagFacade
import com.khj.ragdocument.slack.infrastructure.SlackApiClient
import org.springframework.stereotype.Service

@Service
class SlackFacade(
    private val documentFacade: DocumentFacade,
    private val ragFacade: RagFacade,
    private val slackApiClient: SlackApiClient,
) {

    fun handleEvent(payload: Map<String, Any>): Map<String, Any?>? {
        // URL 검증 (Slack App 설정 시 최초 1회)
        if (payload["type"] == "url_verification") {
            return mapOf("challenge" to payload["challenge"])
        }

        val event = payload["event"] as? Map<*, *> ?: return null
        val text = event["text"] as? String ?: ""
        val channel = event["channel"] as? String ?: return null
        val botId = event["bot_id"]

        if (botId != null) return null

        // 비동기 처리 (Slack은 3초 내 응답 필요)
        Thread {
            try {
                processMessage(event, text, channel)
            } catch (e: Exception) {
                slackApiClient.sendMessage(channel, "오류가 발생했습니다: ${e.message}")
            }
        }.start()

        return null
    }

    private fun processMessage(event: Map<*, *>, text: String, channel: String) {
        @Suppress("UNCHECKED_CAST")
        val files = event["files"] as? List<Map<*, *>>

        if (!files.isNullOrEmpty()) {
            handleFileUpload(channel)
        } else if (text.isNotBlank()) {
            val cleanText = text.replace(Regex("<@[A-Z0-9]+>"), "").trim()
            routeCommand(cleanText, channel)
        }
    }

    private fun handleFileUpload(channel: String) {
        slackApiClient.sendMessage(channel, "파일 처리는 아직 지원하지 않습니다. 텍스트를 직접 입력해주세요.")
    }

    private fun routeCommand(text: String, channel: String) {
        when {
            text.startsWith("/doc") -> handleDocumentIngest(text, channel)
            text.startsWith("/url") -> handleUrlIngest(channel)
            else -> handleQuestion(text, channel)
        }
    }

    private fun handleDocumentIngest(text: String, channel: String) {
        val parts = text.removePrefix("/doc").trim().split("\n", limit = 2)
        val title = parts.getOrElse(0) { "제목 없음" }.trim()
        val content = parts.getOrElse(1) { "" }.trim()

        if (content.isBlank()) {
            slackApiClient.sendMessage(channel, "사용법: /doc 제목\n내용")
            return
        }

        slackApiClient.sendMessage(channel, "문서를 분석 중...")
        val documentId = documentFacade.ingest(title, content, SourceType.TEXT)
        slackApiClient.sendMessage(channel, "문서 \"$title\" 분석 완료! (ID: $documentId)\n질문해주세요.")
    }

    private fun handleUrlIngest(channel: String) {
        slackApiClient.sendMessage(channel, "URL 처리는 5단계에서 구현 예정입니다.")
    }

    private fun handleQuestion(text: String, channel: String) {
        slackApiClient.sendMessage(channel, "문서에서 답변을 검색 중...")
        val answer = ragFacade.ask(text)

        val sourceText = if (answer.sources.isNotEmpty()) {
            "\n\n*출처:*\n" + answer.sources.joinToString("\n") { source ->
                "- ${source.documentTitle} (유사도: ${"%.2f".format(source.score)})"
            }
        } else ""

        slackApiClient.sendMessage(channel, answer.content + sourceText)
    }
}
