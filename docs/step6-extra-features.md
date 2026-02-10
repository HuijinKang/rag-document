# 6단계: 부가 기능 구현 가이드

## 개요

핵심 RAG 기능 위에 요약, 출처 표시 강화, 멀티 문서 비교, 문서 관리 기능을 추가한다.

---

## 기본 패키지 경로

```
src/main/kotlin/com/khj/ragdocument/
```

---

## 기능 1: 문서 요약

### 1-1. SummaryClient 인터페이스

**패키지**: `com.khj.ragdocument.rag.domain`

```kotlin
interface SummaryClient {
    fun summarize(text: String, instruction: String = "핵심 내용을 요약해주세요."): String
}
```

### 1-2. OpenAiSummaryClient 구현체

**패키지**: `com.khj.ragdocument.rag.infrastructure`

```kotlin
@Component
class OpenAiSummaryClient(
    private val webClient: WebClient,
    @Value("\${openai.api-key}") private val apiKey: String,
    @Value("\${openai.model}") private val model: String,
) : SummaryClient {

    override fun summarize(text: String, instruction: String): String {
        val prompt = """
            아래 문서를 다음 지시에 따라 요약하세요.
            
            지시: $instruction
            
            === 문서 ===
            $text
        """.trimIndent()

        val response = webClient.post()
            .uri("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .bodyValue(
                mapOf(
                    "model" to model,
                    "messages" to listOf(
                        mapOf("role" to "system", "content" to "당신은 문서 요약 전문가입니다. 정확하고 간결하게 요약하세요."),
                        mapOf("role" to "user", "content" to prompt),
                    ),
                    "temperature" to 0.3,
                )
            )
            .retrieve()
            .bodyToMono(ChatResponse::class.java)
            .block() ?: throw RuntimeException("요약 API 호출 실패")

        return response.choices.first().message.content
    }
}
```

### 1-3. RagFacade 확장

```kotlin
// RagFacade에 추가
fun summarize(documentId: Long, instruction: String = "핵심 내용을 요약해주세요."): String {
    val document = documentRepository.findById(documentId)
        .orElseThrow { IllegalArgumentException("문서를 찾을 수 없습니다: $documentId") }

    // 문서가 길면 청크 단위로 요약 후 합치기
    val chunks = chunkRepository.findByDocumentId(documentId)

    return if (chunks.size <= 3) {
        // 짧은 문서: 전체 요약
        summaryClient.summarize(document.content, instruction)
    } else {
        // 긴 문서: 청크별 요약 → 최종 요약 (Map-Reduce 방식)
        val chunkSummaries = chunks.map { chunk ->
            summaryClient.summarize(chunk.content, "핵심 내용만 간략히 정리하세요.")
        }
        val combined = chunkSummaries.joinToString("\n\n")
        summaryClient.summarize(combined, instruction)
    }
}
```

### 1-4. REST API

```kotlin
// RagController에 추가
@PostMapping("/summarize")
fun summarize(@RequestBody request: SummarizeRequest): SummarizeResponse {
    val summary = ragFacade.summarize(request.documentId, request.instruction ?: "핵심 내용을 요약해주세요.")
    return SummarizeResponse(summary = summary)
}

data class SummarizeRequest(
    val documentId: Long,
    val instruction: String? = null,
)

data class SummarizeResponse(
    val summary: String,
)
```

### 1-5. Slack 명령어

```
/summary 1                    → 문서 ID 1의 기본 요약
/summary 1 3줄로 요약해줘      → 커스텀 지시 요약
```

---

## 기능 2: 출처 표시 강화

### 2-1. AnswerSource 확장

```kotlin
data class AnswerSource(
    val documentId: Long,
    val documentTitle: String,
    val chunkIndex: Int,
    val content: String,
    val score: Float,
    val highlightedText: String,   // 답변에 사용된 핵심 문장
)
```

### 2-2. 프롬프트 개선

RagFacade의 `buildPrompt`에서 출처 명시를 강화한다.

```kotlin
private fun buildPrompt(question: String, searchResults: List<SearchResult>): String {
    val context = searchResults.mapIndexed { index, result ->
        "[문서 ${index + 1}] (문서명: ${result.documentTitle})\n${result.content}"
    }.joinToString("\n\n")

    return """
        아래 문서 내용을 참고하여 질문에 답변하세요.
        
        규칙:
        1. 반드시 제공된 문서 내용만을 기반으로 답변하세요.
        2. 답변에 사용한 문서를 [문서 N] 형식으로 인라인 표시하세요.
        3. 문서에 없는 내용은 "제공된 문서에서 해당 정보를 찾을 수 없습니다."라고 답변하세요.
        4. 답변에 사용한 핵심 문장을 "출처 인용: ..." 형태로 마지막에 추가하세요.
        
        === 참고 문서 ===
        $context
        
        === 질문 ===
        $question
    """.trimIndent()
}
```

---

## 기능 3: 멀티 문서 비교

### 3-1. RagFacade에 비교 기능 추가

```kotlin
fun compareDocuments(documentIds: List<Long>, aspect: String = "주요 내용"): String {
    val documents = documentIds.map { id ->
        documentRepository.findById(id)
            .orElseThrow { IllegalArgumentException("문서를 찾을 수 없습니다: $id") }
    }

    val documentContents = documents.mapIndexed { index, doc ->
        "[문서 ${index + 1}: ${doc.title}]\n${doc.content.take(2000)}"  // 토큰 제한 고려
    }.joinToString("\n\n")

    val prompt = """
        아래 문서들을 비교 분석하세요.
        
        비교 관점: $aspect
        
        === 문서들 ===
        $documentContents
        
        === 요청 ===
        각 문서의 공통점과 차이점을 정리하고, 비교 관점에서 분석해주세요.
    """.trimIndent()

    return chatClient.generate(prompt)
}
```

### 3-2. REST API

```kotlin
@PostMapping("/compare")
fun compare(@RequestBody request: CompareRequest): CompareResponse {
    val result = ragFacade.compareDocuments(request.documentIds, request.aspect ?: "주요 내용")
    return CompareResponse(result = result)
}

data class CompareRequest(
    val documentIds: List<Long>,
    val aspect: String? = null,
)

data class CompareResponse(
    val result: String,
)
```

---

## 기능 4: 문서 관리

### 4-1. DocumentFacade에 관리 기능 추가

```kotlin
// 문서 목록 조회
fun listDocuments(): List<DocumentSummary> {
    return documentRepository.findAll().map { doc ->
        DocumentSummary(
            id = doc.id,
            title = doc.title,
            sourceType = doc.sourceType,
            createdAt = doc.createdAt,
        )
    }
}

// 문서 삭제
fun deleteDocument(documentId: Long) {
    // 1. Milvus에서 벡터 삭제
    val chunks = chunkRepository.findByDocumentId(documentId)
    chunks.forEach { chunk ->
        vectorStoreRepository.delete(chunk.id)
    }

    // 2. PostgreSQL에서 청크 삭제
    chunkRepository.deleteAll(chunks)

    // 3. PostgreSQL에서 문서 삭제
    documentRepository.deleteById(documentId)
}

data class DocumentSummary(
    val id: Long,
    val title: String,
    val sourceType: SourceType,
    val createdAt: LocalDateTime,
)
```

### 4-2. VectorStoreRepository에 delete 추가

```kotlin
interface VectorStoreRepository {
    fun save(chunkId: Long, embedding: List<Float>)
    fun saveBatch(chunkEmbeddings: Map<Long, List<Float>>)
    fun search(queryEmbedding: List<Float>, topK: Int = 5): List<VectorSearchResult>
    fun delete(chunkId: Long)       // 추가
}
```

### 4-3. REST API

```kotlin
// DocumentController에 추가

@GetMapping
fun listDocuments(): List<DocumentSummary> {
    return documentFacade.listDocuments()
}

@DeleteMapping("/{documentId}")
fun deleteDocument(@PathVariable documentId: Long): Map<String, String> {
    documentFacade.deleteDocument(documentId)
    return mapOf("message" to "문서가 삭제되었습니다.")
}
```

### 4-4. Slack 명령어

```
/list              → 등록된 문서 목록 조회
/delete 3          → 문서 ID 3 삭제
/compare 1,2       → 문서 1, 2 비교
/summary 1         → 문서 1 요약
```

---

## 완료 기준

- [ ] 문서 요약이 동작한다 (기본 요약 + 커스텀 지시)
- [ ] 긴 문서도 Map-Reduce 방식으로 요약된다
- [ ] 답변에 출처가 인라인으로 표시된다
- [ ] 여러 문서를 비교 분석할 수 있다
- [ ] 문서 목록 조회, 삭제가 가능하다
- [ ] Slack에서 /summary, /list, /delete, /compare 명령어가 동작한다