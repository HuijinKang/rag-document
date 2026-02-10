# 2단계: RAG 코어 구현 가이드

## 개요

텍스트 입력 → 청크 분할 → 임베딩 생성 → Milvus 저장까지의 RAG 파이프라인 핵심을 구현한다.
이 단계에서는 Slack 연동 없이, **REST API로 텍스트를 직접 입력**하여 테스트한다.

---

## 기본 패키지 경로

```
src/main/kotlin/com/khj/ragdocument/
```

---

## 구현 순서

### 1. Document 도메인 모델

**패키지**: `com.khj.ragdocument.document.domain`

#### Document.kt (엔티티)

```kotlin
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
    val sourceType: SourceType,       // TEXT, PDF, DOCX, URL

    val sourceUrl: String? = null,

    val createdAt: LocalDateTime = LocalDateTime.now(),
) {
    fun splitToChunks(strategy: ChunkingStrategy): List<Chunk> {
        return strategy.split(this)
    }
}
```

#### SourceType.kt

```kotlin
enum class SourceType {
    TEXT, PDF, DOCX, URL
}
```

#### Chunk.kt (값 객체)

```kotlin
@Entity
@Table(name = "chunks")
class Chunk(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    val documentId: Long,

    @Column(columnDefinition = "TEXT")
    val content: String,

    val chunkIndex: Int,            // 문서 내 순서

    val tokenCount: Int,            // 토큰 수 (참고용)
)
```

#### ChunkingStrategy.kt (도메인 전략 객체)

```kotlin
class ChunkingStrategy(
    private val maxChunkSize: Int = 500,    // 최대 글자 수
    private val overlap: Int = 50,           // 청크 간 겹침 글자 수
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
                    tokenCount = chunkText.length / 4,  // 대략적 토큰 추정
                )
            )
            start += maxChunkSize - overlap
            index++
        }

        return chunks
    }
}
```

> 이 전략은 단순한 고정 크기 분할이다. 이후 문장 단위 분할, 토큰 기반 분할 등으로 고도화할 수 있다.

#### DocumentRepository.kt (인터페이스)

```kotlin
interface DocumentRepository : JpaRepository<Document, Long>
```

#### ChunkRepository.kt (인터페이스)

```kotlin
interface ChunkRepository : JpaRepository<Chunk, Long> {
    fun findByDocumentId(documentId: Long): List<Chunk>
}
```

---

### 2. Embedding 도메인

**패키지**: `com.khj.ragdocument.embedding.domain`

#### EmbeddingClient.kt (인터페이스 - DIP)

```kotlin
interface EmbeddingClient {
    fun embed(text: String): List<Float>
    fun embedBatch(texts: List<String>): List<List<Float>>
}
```

**패키지**: `com.khj.ragdocument.embedding.infrastructure`

#### OpenAiEmbeddingClient.kt (구현체)

```kotlin
@Component
class OpenAiEmbeddingClient(
    private val webClient: WebClient,
    @Value("\${openai.api-key}") private val apiKey: String,
    @Value("\${openai.embedding-model}") private val model: String,
) : EmbeddingClient {

    override fun embed(text: String): List<Float> {
        return embedBatch(listOf(text)).first()
    }

    override fun embedBatch(texts: List<String>): List<List<Float>> {
        val response = webClient.post()
            .uri("https://api.openai.com/v1/embeddings")
            .header("Authorization", "Bearer $apiKey")
            .bodyValue(
                mapOf(
                    "model" to model,
                    "input" to texts,
                )
            )
            .retrieve()
            .bodyToMono(EmbeddingResponse::class.java)
            .block() ?: throw RuntimeException("임베딩 API 호출 실패")

        return response.data
            .sortedBy { it.index }
            .map { it.embedding }
    }
}
```

**패키지**: `com.khj.ragdocument.embedding.infrastructure.dto`

#### EmbeddingResponse.kt (응답 DTO)

```kotlin
data class EmbeddingResponse(
    val data: List<EmbeddingData>,
)

data class EmbeddingData(
    val index: Int,
    val embedding: List<Float>,
)
```

---

### 3. VectorStore 도메인

**패키지**: `com.khj.ragdocument.vectorstore.domain`

#### VectorStoreRepository.kt (인터페이스 - DIP)

```kotlin
interface VectorStoreRepository {
    fun save(chunkId: Long, embedding: List<Float>)
    fun saveBatch(chunkEmbeddings: Map<Long, List<Float>>)
    fun search(queryEmbedding: List<Float>, topK: Int = 5): List<VectorSearchResult>
}

data class VectorSearchResult(
    val chunkId: Long,
    val score: Float,
)
```

**패키지**: `com.khj.ragdocument.vectorstore.infrastructure`

#### MilvusVectorStore.kt (구현체)

```kotlin
@Component
class MilvusVectorStore(
    @Value("\${milvus.host}") private val host: String,
    @Value("\${milvus.port}") private val port: Int,
) : VectorStoreRepository {

    private val collectionName = "document_chunks"
    private val dimension = 1536  // text-embedding-3-small 차원

    @PostConstruct
    fun init() {
        // Milvus 연결 및 컬렉션 생성
        // 컬렉션이 없으면 생성한다
        // 필드: chunk_id (Int64, PK), embedding (FloatVector, dim=1536)
    }

    override fun save(chunkId: Long, embedding: List<Float>) {
        saveBatch(mapOf(chunkId to embedding))
    }

    override fun saveBatch(chunkEmbeddings: Map<Long, List<Float>>) {
        // Milvus에 벡터 삽입
    }

    override fun search(queryEmbedding: List<Float>, topK: Int): List<VectorSearchResult> {
        // Milvus에서 유사도 검색 (코사인 유사도)
        // VectorSearchResult 리스트 반환
    }
}
```

> Milvus SDK 사용법은 공식 문서를 참고한다: https://milvus.io/docs

---

### 4. Document Application (Facade)

**패키지**: `com.khj.ragdocument.document.application`

#### DocumentFacade.kt

```kotlin
@Service
@Transactional
class DocumentFacade(
    private val documentRepository: DocumentRepository,
    private val chunkRepository: ChunkRepository,
    private val embeddingClient: EmbeddingClient,
    private val vectorStoreRepository: VectorStoreRepository,
) {
    private val chunkingStrategy = ChunkingStrategy()

    fun ingest(title: String, content: String, sourceType: SourceType): Long {
        // 1. Document 저장
        val document = documentRepository.save(
            Document(title = title, content = content, sourceType = sourceType)
        )

        // 2. 청크 분할
        val chunks = document.splitToChunks(chunkingStrategy)
        val savedChunks = chunkRepository.saveAll(chunks)

        // 3. 임베딩 생성
        val texts = savedChunks.map { it.content }
        val embeddings = embeddingClient.embedBatch(texts)

        // 4. Milvus에 벡터 저장
        val chunkEmbeddings = savedChunks.zip(embeddings)
            .associate { (chunk, embedding) -> chunk.id to embedding }
        vectorStoreRepository.saveBatch(chunkEmbeddings)

        return document.id
    }
}
```

---

### 5. Document Presentation (REST API)

**패키지**: `com.khj.ragdocument.document.presentation`

#### DocumentController.kt

```kotlin
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
```

**패키지**: `com.khj.ragdocument.document.presentation.dto`

#### DocumentIngestRequest.kt

```kotlin
data class DocumentIngestRequest(
    val title: String,
    val content: String,
)
```

#### DocumentIngestResponse.kt

```kotlin
data class DocumentIngestResponse(
    val documentId: Long,
    val message: String,
)
```

---

### 6. Config

**패키지**: `com.khj.ragdocument.global.config`

#### WebClientConfig.kt

```kotlin
@Configuration
class WebClientConfig {
    @Bean
    fun webClient(): WebClient {
        return WebClient.builder()
            .codecs { it.defaultCodecs().maxInMemorySize(10 * 1024 * 1024) }  // 10MB
            .build()
    }
}
```

---

## 테스트 방법

### 1. 인프라 실행

```bash
docker-compose up -d
```

### 2. 애플리케이션 실행

```bash
./gradlew bootRun
```

### 3. 문서 입력 테스트 (curl)

```bash
curl -X POST http://localhost:8080/api/documents \
  -H "Content-Type: application/json" \
  -d '{
    "title": "테스트 문서",
    "content": "이것은 RAG 파이프라인을 테스트하기 위한 문서입니다. 문서의 내용이 청크로 분할되고, 임베딩으로 변환되어 Milvus에 저장됩니다."
  }'
```

### 4. 확인 포인트

- [ ] PostgreSQL에 `documents` 테이블에 문서가 저장된다
- [ ] PostgreSQL에 `chunks` 테이블에 청크가 저장된다
- [ ] OpenAI 임베딩 API가 정상 호출된다
- [ ] Milvus에 벡터가 저장된다

---

## 주의사항

- OpenAI API 키가 환경변수에 설정되어 있어야 한다
- Milvus가 정상 기동된 후 애플리케이션을 실행해야 한다
- 임베딩 API 호출 시 비용이 발생한다 (text-embedding-3-small은 매우 저렴)