# 3단계: Q&A 기능 구현 가이드

## 개요

사용자 질문 → 임베딩 → Milvus 유사도 검색 → 검색된 청크 + 질문을 GPT에 전달 → 문서 기반 답변 생성.
2단계에서 저장한 벡터를 활용하여, 문서 기반 질의응답 기능을 구현한다.

---

## 기본 패키지 경로

```
src/main/kotlin/com/khj/ragdocument/
```

---

## 구현 순서

### 1. RAG 도메인 모델

**패키지**: `com.khj.ragdocument.rag.domain`

#### Answer.kt (값 객체)

```kotlin
data class Answer(
    val content: String,
    val sources: List<AnswerSource>,
)

data class AnswerSource(
    val documentId: Long,
    val documentTitle: String,
    val chunkIndex: Int,
    val content: String,
    val score: Float,
)
```

#### ChatClient.kt (인터페이스 - DIP)

```kotlin
interface ChatClient {
    fun generate(prompt: String): String
}
```

---

### 2. ChatClient 구현체

**패키지**: `com.khj.ragdocument.rag.infrastructure`

#### OpenAiChatClient.kt

```kotlin
@Component
class OpenAiChatClient(
    private val webClient: WebClient,
    @Value("\${openai.api-key}") private val apiKey: String,
    @Value("\${openai.model}") private val model: String,
) : ChatClient {

    override fun generate(prompt: String): String {
        val response = webClient.post()
            .uri("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .bodyValue(
                mapOf(
                    "model" to model,
                    "messages" to listOf(
                        mapOf("role" to "system", "content" to SYSTEM_PROMPT),
                        mapOf("role" to "user", "content" to prompt),
                    ),
                    "temperature" to 0.3,
                )
            )
            .retrieve()
            .bodyToMono(ChatResponse::class.java)
            .block() ?: throw RuntimeException("Chat API 호출 실패")

        return response.choices.first().message.content
    }

    companion object {
        private const val SYSTEM_PROMPT = """
            당신은 문서 기반 질의응답 어시스턴트입니다.
            제공된 문서 내용만을 기반으로 정확하게 답변하세요.
            문서에 없는 내용은 "제공된 문서에서 해당 정보를 찾을 수 없습니다."라고 답변하세요.
            답변 시 어떤 문서의 어느 부분을 참고했는지 출처를 함께 제시하세요.
        """
    }
}

// 응답 DTO
data class ChatResponse(
    val choices: List<ChatChoice>,
)

data class ChatChoice(
    val message: ChatMessage,
)

data class ChatMessage(
    val role: String,
    val content: String,
)
```

---

### 3. RAG Application (Facade)

**패키지**: `com.khj.ragdocument.rag.application`

#### SearchResult.kt (검색 결과 조합 객체)

> Milvus 검색 결과(score)와 청크 데이터(content, documentId, chunkIndex)를 합친 Facade 내부용 객체.
> 도메인 개념이 아닌 유스케이스 흐름에서 생기는 중간 데이터이므로 Application 레이어에 둔다.

```kotlin
data class SearchResult(
    val chunkId: Long,
    val content: String,
    val score: Float,
    val documentId: Long,
    val chunkIndex: Int,
)
```

#### RagFacade.kt

```kotlin
@Service
@Transactional(readOnly = true)
class RagFacade(
    private val embeddingClient: EmbeddingClient,
    private val vectorStoreRepository: VectorStoreRepository,
    private val chunkRepository: ChunkRepository,
    private val documentRepository: DocumentRepository,
    private val chatClient: ChatClient,
) {

    fun ask(question: String, topK: Int = 5): Answer {
        // 1. 질문 임베딩
        val queryEmbedding = embeddingClient.embed(question)

        // 2. Milvus에서 유사 청크 검색
        val vectorResults = vectorStoreRepository.search(queryEmbedding, topK)

        // 3. 청크 내용 조회
        val chunkIds = vectorResults.map { it.chunkId }
        val chunks = chunkRepository.findAllById(chunkIds)
        val chunkMap = chunks.associateBy { it.id }

        // 4. 검색 결과 조합
        val searchResults = vectorResults.mapNotNull { result ->
            val chunk = chunkMap[result.chunkId] ?: return@mapNotNull null
            SearchResult(
                chunkId = chunk.id,
                content = chunk.content,
                score = result.score,
                documentId = chunk.documentId,
                chunkIndex = chunk.chunkIndex,
            )
        }

        // 5. 프롬프트 구성
        val prompt = buildPrompt(question, searchResults)

        // 6. GPT 답변 생성
        val answerContent = chatClient.generate(prompt)

        // 7. 출처 정보 구성
        val sources = searchResults.map { result ->
            val document = documentRepository.findById(result.documentId).orElse(null)
            AnswerSource(
                documentId = result.documentId,
                documentTitle = document?.title ?: "알 수 없음",
                chunkIndex = result.chunkIndex,
                content = result.content,
                score = result.score,
            )
        }

        return Answer(content = answerContent, sources = sources)
    }

    private fun buildPrompt(question: String, searchResults: List<SearchResult>): String {
        val context = searchResults.mapIndexed { index, result ->
            "[문서 ${index + 1}] (유사도: ${result.score})\n${result.content}"
        }.joinToString("\n\n")

        return """
            아래 문서 내용을 참고하여 질문에 답변하세요.

            === 참고 문서 ===
            $context

            === 질문 ===
            $question

            === 답변 형식 ===
            답변 내용을 작성하고, 참고한 문서 번호를 [문서 N] 형식으로 표시하세요.
        """.trimIndent()
    }
}
```

---

### 4. RAG Presentation (REST API)

**패키지**: `com.khj.ragdocument.rag.presentation`

#### RagController.kt

```kotlin
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

data class AskRequest(
    val question: String,
    val topK: Int? = 5,
)

data class AskResponse(
    val answer: String,
    val sources: List<SourceResponse>,
)

data class SourceResponse(
    val documentTitle: String,
    val chunkIndex: Int,
    val content: String,
    val score: Float,
)
```

---

## 전체 흐름 요약

```
POST /api/rag/ask { "question": "..." }
      ↓
RagController → RagFacade.ask()
      ↓
  1. embeddingClient.embed(question)       → 질문을 벡터로 변환
  2. vectorStoreRepository.search()        → Milvus에서 유사 청크 검색
  3. chunkRepository.findAllById()         → 청크 내용 조회 (PostgreSQL)
  4. documentRepository.findById()         → 문서 정보 조회 (출처용)
  5. buildPrompt()                         → 검색 결과 + 질문으로 프롬프트 구성
  6. chatClient.generate(prompt)           → GPT가 문서 기반 답변 생성
      ↓
응답: { answer: "...", sources: [...] }
```

---

## 테스트 방법

### 1. 사전 조건

- 2단계에서 문서가 정상적으로 저장되어 있어야 한다
- Docker (Milvus, PostgreSQL)가 실행 중이어야 한다

### 2. 문서 먼저 입력 (2단계 API)

```bash
curl -X POST http://localhost:8080/api/documents \
  -H "Content-Type: application/json" \
  -d '{
    "title": "회사 연차 정책",
    "content": "직원은 입사 1년 후 15일의 연차를 부여받습니다. 연차는 매년 1월 1일 기준으로 갱신됩니다. 미사용 연차는 다음 해로 이월되지 않습니다. 반차는 0.5일로 계산됩니다. 병가는 연간 3일까지 유급으로 처리됩니다."
  }'
```

### 3. 질문 테스트

```bash
curl -X POST http://localhost:8080/api/rag/ask \
  -H "Content-Type: application/json" \
  -d '{
    "question": "연차는 몇 일이야?"
  }'
```

### 4. 기대 응답 예시

```json
{
  "answer": "입사 1년 후 15일의 연차가 부여됩니다. [문서 1]",
  "sources": [
    {
      "documentTitle": "회사 연차 정책",
      "chunkIndex": 0,
      "content": "직원은 입사 1년 후 15일의 연차를 부여받습니다...",
      "score": 0.92
    }
  ]
}
```

---

## 완료 기준

- [ ] 질문을 보내면 문서 기반 답변이 반환된다
- [ ] 답변에 출처(문서명, 청크 내용, 유사도 점수)가 포함된다
- [ ] 문서에 없는 내용을 질문하면 "찾을 수 없습니다" 류의 답변이 온다
- [ ] 여러 문서를 저장한 후 질문하면 관련 문서에서 답변을 가져온다