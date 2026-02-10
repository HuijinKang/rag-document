# 5단계: 문서 파싱 구현 가이드

## 개요

PDF, DOCX 파일 업로드와 URL 웹페이지 크롤링을 구현한다.
4단계까지는 텍스트 직접 입력만 지원했으나, 이 단계에서 다양한 포맷의 문서를 처리할 수 있게 확장한다.

---

## 기본 패키지 경로

```
src/main/kotlin/com/khj/ragdocument/
```

---

## 구현 순서

### 1. DocumentParser 인터페이스 (DIP)

**패키지**: `com.khj.ragdocument.document.domain`

#### DocumentParser.kt

```kotlin
interface DocumentParser {
    fun parse(input: ByteArray): String
    fun supports(mimeType: String): Boolean
}
```

---

### 2. PDF 파서

**패키지**: `com.khj.ragdocument.document.infrastructure`

#### PdfParser.kt

```kotlin
@Component
class PdfParser : DocumentParser {

    override fun parse(input: ByteArray): String {
        val document = PDDocument.load(input)
        return document.use { doc ->
            val stripper = PDFTextStripper()
            stripper.getText(doc)
        }
    }

    override fun supports(mimeType: String): Boolean {
        return mimeType == "application/pdf"
    }
}
```

---

### 3. DOCX 파서

#### DocxParser.kt

```kotlin
@Component
class DocxParser : DocumentParser {

    override fun parse(input: ByteArray): String {
        val document = XWPFDocument(ByteArrayInputStream(input))
        return document.use { doc ->
            doc.paragraphs.joinToString("\n") { it.text }
        }
    }

    override fun supports(mimeType: String): Boolean {
        return mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }
}
```

---

### 4. 웹페이지 파서

#### WebPageParser.kt

```kotlin
@Component
class WebPageParser : DocumentParser {

    override fun parse(input: ByteArray): String {
        val url = String(input)  // URL 문자열로 받음
        val document = Jsoup.connect(url)
            .userAgent("Mozilla/5.0")
            .timeout(10000)
            .get()

        // 불필요한 요소 제거
        document.select("script, style, nav, footer, header, aside").remove()

        // 본문 텍스트 추출
        return document.body().text()
    }

    override fun supports(mimeType: String): Boolean {
        return mimeType == "text/url"
    }
}
```

---

### 5. DocumentParserFactory

**패키지**: `com.khj.ragdocument.document.infrastructure`

여러 파서 중 적절한 것을 선택하는 팩토리.

#### DocumentParserFactory.kt

```kotlin
@Component
class DocumentParserFactory(
    private val parsers: List<DocumentParser>,
) {
    fun getParser(mimeType: String): DocumentParser {
        return parsers.find { it.supports(mimeType) }
            ?: throw IllegalArgumentException("지원하지 않는 파일 형식입니다: $mimeType")
    }
}
```

---

### 6. DocumentFacade 확장

**패키지**: `com.khj.ragdocument.document.application`

기존 `DocumentFacade`에 파일/URL 처리 메서드를 추가한다.

```kotlin
@Service
@Transactional
class DocumentFacade(
    private val documentRepository: DocumentRepository,
    private val chunkRepository: ChunkRepository,
    private val embeddingClient: EmbeddingClient,
    private val vectorStoreRepository: VectorStoreRepository,
    private val documentParserFactory: DocumentParserFactory,
) {
    private val chunkingStrategy = ChunkingStrategy()

    // 기존: 텍스트 직접 입력
    fun ingest(title: String, content: String, sourceType: SourceType): Long {
        return processDocument(title, content, sourceType, null)
    }

    // 신규: 파일 업로드 (PDF, DOCX)
    fun ingestFile(fileName: String, fileBytes: ByteArray, mimeType: String): Long {
        val parser = documentParserFactory.getParser(mimeType)
        val content = parser.parse(fileBytes)
        val sourceType = when (mimeType) {
            "application/pdf" -> SourceType.PDF
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> SourceType.DOCX
            else -> SourceType.TEXT
        }
        return processDocument(fileName, content, sourceType, null)
    }

    // 신규: URL 크롤링
    fun ingestUrl(url: String): Long {
        val parser = documentParserFactory.getParser("text/url")
        val content = parser.parse(url.toByteArray())
        return processDocument(url, content, SourceType.URL, url)
    }

    private fun processDocument(
        title: String,
        content: String,
        sourceType: SourceType,
        sourceUrl: String?,
    ): Long {
        // 1. Document 저장
        val document = documentRepository.save(
            Document(
                title = title,
                content = content,
                sourceType = sourceType,
                sourceUrl = sourceUrl,
            )
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

### 7. REST API 확장

**패키지**: `com.khj.ragdocument.document.presentation`

#### DocumentController.kt 수정

```kotlin
@RestController
@RequestMapping("/api/documents")
class DocumentController(
    private val documentFacade: DocumentFacade,
) {
    // 기존: 텍스트 입력
    @PostMapping
    fun ingest(@RequestBody request: DocumentIngestRequest): DocumentIngestResponse {
        val documentId = documentFacade.ingest(
            title = request.title,
            content = request.content,
            sourceType = SourceType.TEXT,
        )
        return DocumentIngestResponse(documentId = documentId, message = "문서 분석 완료")
    }

    // 신규: 파일 업로드
    @PostMapping("/upload")
    fun uploadFile(@RequestParam("file") file: MultipartFile): DocumentIngestResponse {
        val documentId = documentFacade.ingestFile(
            fileName = file.originalFilename ?: "unknown",
            fileBytes = file.bytes,
            mimeType = file.contentType ?: "",
        )
        return DocumentIngestResponse(documentId = documentId, message = "파일 분석 완료")
    }

    // 신규: URL 크롤링
    @PostMapping("/url")
    fun ingestUrl(@RequestBody request: UrlIngestRequest): DocumentIngestResponse {
        val documentId = documentFacade.ingestUrl(request.url)
        return DocumentIngestResponse(documentId = documentId, message = "URL 분석 완료")
    }
}

data class UrlIngestRequest(
    val url: String,
)
```

---

### 8. Slack Bot 파일 처리 연동

4단계에서 미구현이었던 파일 업로드 처리를 구현한다.

#### SlackEventController.kt 수정 (handleFileUpload)

```kotlin
private fun handleFileUpload(files: List<Map<String, Any>>, channel: String) {
    slackApiClient.sendMessage(channel, "📄 문서를 받았습니다. 분석 중...")

    files.forEach { file ->
        val fileName = file["name"] as? String ?: "unknown"
        val fileUrl = file["url_private_download"] as? String ?: return@forEach
        val mimeType = file["mimetype"] as? String ?: ""

        try {
            val fileBytes = slackApiClient.downloadFile(fileUrl)
            val documentId = documentFacade.ingestFile(fileName, fileBytes, mimeType)
            slackApiClient.sendMessage(channel, "✅ \"$fileName\" 분석 완료! (ID: $documentId)\n질문해주세요.")
        } catch (e: IllegalArgumentException) {
            slackApiClient.sendMessage(channel, "⚠️ 지원하지 않는 파일 형식입니다: $mimeType")
        } catch (e: Exception) {
            slackApiClient.sendMessage(channel, "❌ 파일 처리 중 오류 발생: ${e.message}")
        }
    }
}
```

#### SlackEventController.kt 수정 (URL 처리)

```kotlin
cleanText.startsWith("/url") -> {
    val url = cleanText.removePrefix("/url").trim()
    if (url.isBlank()) {
        slackApiClient.sendMessage(channel, "📝 사용법: `/url https://...`")
        return
    }
    slackApiClient.sendMessage(channel, "🌐 URL을 분석 중...")
    try {
        val documentId = documentFacade.ingestUrl(url)
        slackApiClient.sendMessage(channel, "✅ URL 분석 완료! (ID: $documentId)\n질문해주세요.")
    } catch (e: Exception) {
        slackApiClient.sendMessage(channel, "❌ URL 처리 중 오류 발생: ${e.message}")
    }
}
```

---

## 지원 포맷 정리

| 포맷 | MIME Type | 파서 | 입력 방식 |
|---|---|---|---|
| PDF | application/pdf | PdfParser | 파일 업로드 |
| DOCX | application/vnd.openxmlformats-officedocument.wordprocessingml.document | DocxParser | 파일 업로드 |
| URL | text/url (커스텀) | WebPageParser | URL 텍스트 입력 |
| 텍스트 | - | 직접 처리 | 텍스트 직접 입력 |

---

## 테스트 방법

### PDF 업로드 (REST API)

```bash
curl -X POST http://localhost:8080/api/documents/upload \
  -F "file=@test.pdf"
```

### DOCX 업로드 (REST API)

```bash
curl -X POST http://localhost:8080/api/documents/upload \
  -F "file=@test.docx"
```

### URL 크롤링 (REST API)

```bash
curl -X POST http://localhost:8080/api/documents/url \
  -H "Content-Type: application/json" \
  -d '{"url": "https://example.com/article"}'
```

### Slack에서 테스트

```
(PDF 파일 드래그 앤 드롭)
봇: 📄 문서를 받았습니다. 분석 중...
봇: ✅ "report.pdf" 분석 완료! (ID: 3)

/url https://example.com/article
봇: 🌐 URL을 분석 중...
봇: ✅ URL 분석 완료! (ID: 4)
```

---

## 완료 기준

- [ ] PDF 파일을 업로드하면 텍스트가 추출되고 RAG 파이프라인이 동작한다
- [ ] DOCX 파일을 업로드하면 텍스트가 추출되고 RAG 파이프라인이 동작한다
- [ ] URL을 입력하면 웹페이지 본문이 추출되고 RAG 파이프라인이 동작한다
- [ ] Slack에서 파일을 보내면 자동으로 파싱 + 저장된다
- [ ] 지원하지 않는 파일 형식에 대해 적절한 에러 메시지가 반환된다

---

## 주의사항

- PDF 중 스캔 이미지 기반 PDF는 텍스트 추출이 안 된다 (OCR 필요, 추후 고도화)
- 웹 크롤링 시 로그인 필요 페이지는 접근 불가
- 대용량 파일은 임베딩 API 호출 비용이 증가할 수 있다
- Slack 파일 다운로드 시 Bot Token 인증이 필요하다