# rag-document

## 프로젝트 개요

AI 기반 문서 요약 & Q&A 시스템 (RAG)

사용자가 문서(PDF, DOCX, URL, 텍스트)를 전달하면 AI가 내용을 분석하고, 질문에 대해 문서 기반으로 정확하게 답변하는 시스템.

---

## 기술 스택

| 구분 | 기술 | 비고 |
|---|---|---|
| 언어 | Kotlin | |
| 프레임워크 | Spring Boot 4.0.2 | Java 21 |
| AI (생성) | OpenAI API (GPT-4o) | 질문 답변, 요약 생성 |
| AI (임베딩) | OpenAI API (text-embedding-3-small) | 텍스트 → 벡터 변환 |
| 벡터 DB | Milvus | Docker, 셀프 호스팅 |
| RDB | PostgreSQL | 메타데이터, 사용자 이력 |
| 문서 파싱 | Apache PDFBox (PDF), Apache POI (DOCX), Jsoup (URL) | |
| 인터페이스 | Slack Bot (1차) → 웹 UI 확장 가능 | |
| 배포 | 로컬 개발 → 이후 클라우드 배포 | |

---

## 핵심 아키텍처 (RAG 파이프라인)

```
[문서 입력] PDF / DOCX / URL / 텍스트
      ↓
[텍스트 추출] PDFBox / POI / Jsoup
      ↓
[청크 분할] 적절한 크기로 텍스트 분할
      ↓
[임베딩 생성] OpenAI text-embedding API → 벡터 변환
      ↓
[벡터 저장] Milvus에 임베딩 + 메타데이터 저장
      ↓
[사용자 질문] 질문 텍스트 → 임베딩 변환
      ↓
[유사도 검색] Milvus에서 유사한 청크 Top-K 검색
      ↓
[답변 생성] 검색된 청크 + 질문을 GPT-4o에 전달 → 문서 기반 답변
```

---

## 주요 기능

### 1. 문서 입력
- PDF 파일 업로드
- DOCX 파일 업로드
- URL 링크 → 웹페이지 본문 크롤링
- 텍스트 직접 입력

### 2. 문서 처리 (RAG 파이프라인)
- 텍스트 추출
- 청크 분할 (chunk splitting)
- OpenAI 임베딩 API로 벡터 변환
- Milvus에 벡터 + 메타데이터 저장

### 3. Q&A (질의응답)
- 사용자 질문 → 임베딩 → Milvus 유사도 검색
- 검색된 청크 + 질문을 GPT-4o에 전달
- 문서 기반 답변 생성
- 출처 표시 (문서명, 페이지, 위치 등)

### 4. 요약
- 전체 문서 요약
- 핵심 포인트 추출
- 커스텀 요약 ("3줄로 요약해줘" 등)

---

## 사용 흐름 (Slack Bot)

```
사용자 → Slack에서 PDF/링크/텍스트 전송
  ↓
봇 → "문서를 받았습니다. 분석 중... 완료! 질문해주세요."
  ↓
사용자 → "이 문서에서 핵심 내용이 뭐야?"
  ↓
봇 → 문서 기반 답변 + 출처 표시
```

---

## 개발 단계

| 단계 | 내용 | 설명 |
|---|---|---|
| 1단계 | 프로젝트 세팅 | Spring Boot + Docker (Milvus, PostgreSQL) 구성 |
| 2단계 | RAG 코어 | 텍스트 입력 → 청크 분할 → 임베딩 → Milvus 저장 |
| 3단계 | Q&A 기능 | 질문 → 유사 검색 → GPT 답변 생성 |
| 4단계 | Slack Bot 연동 | Slack 봇으로 문서 입력 + 질문/답변 인터페이스 |
| 5단계 | 문서 파싱 | PDF / DOCX / URL 파싱 추가 |
| 6단계 | 부가 기능 | 요약, 출처 표시, 멀티 문서 비교 등 |
| 7단계 | 배포 (선택) | 클라우드 배포 |

---

## 아키텍처

- **DDD (Domain-Driven Design)** 기반 도메인 패키지 분리
- **Layered Architecture**: Presentation → Application(Facade) → Domain → Infrastructure

### 레이어 역할

| 레이어 | 역할 | Repository 접근 | 예시 |
|---|---|---|---|
| Presentation | 외부 요청 수신 (API, Slack 이벤트) | ❌ | SlackEventController |
| Application (Facade) | 유스케이스 오케스트레이션, 흐름 제어, Repository 호출 | ✅ | DocumentFacade, RagFacade |
| Domain | 엔티티, 값 객체, 도메인 전략 객체, Repository/Client 인터페이스 (순수 비즈니스 규칙) | ❌ | Document, Chunk, ChunkingStrategy |
| Infrastructure | Repository 구현체, 외부 API 연동 | - | DocumentJpaRepository, OpenAiClient |

### 아키텍처 규칙

- **Domain은 어디에도 의존하지 않는다.** 순수 비즈니스 로직만 담당한다.
- **Application(Facade)이 Repository를 호출한다.** 유스케이스 흐름 제어와 영속성 처리를 담당한다.
- **Repository/Client 인터페이스는 Domain에, 구현체는 Infrastructure에 둔다.** (DIP 적용)
- **Domain Service(전략 객체)는 엔티티 하나에 넣기 어려운 순수 로직일 때만 사용한다.** (예: 청크 분할 알고리즘)
- **비즈니스 로직은 가능한 한 도메인 엔티티 안에 둔다.** (Rich Domain Model)
- **Deprecated된 메서드/함수는 사용하지 않는다.** 라이브러리의 최신 API를 확인하고 대체 메서드를 사용한다.
- **DTO는 별도 dto 패키지에 분리한다.** Presentation DTO는 `presentation/dto/`, Infrastructure DTO는 `infrastructure/dto/`에 둔다.

### Facade 흐름 예시

```
[Slack 메시지 수신] → SlackEventController (Presentation)
      ↓
DocumentFacade.ingestDocument() (Application)
      ↓
  Document.create(text)                → 도메인 객체 생성 (Domain)
  document.splitToChunks()             → 청크 분할 (Domain 로직)
  documentRepository.save(document)    → 저장 (Application이 호출)
  embeddingClient.embed(chunks)        → 임베딩 생성 (Infrastructure)
  vectorStoreRepository.saveAll()      → Milvus 저장 (Application이 호출)
```

```
[사용자 질문] → SlackEventController (Presentation)
      ↓
RagFacade.ask() (Application)
      ↓
  embeddingClient.embed(question)      → 질문 임베딩 (Infrastructure)
  vectorStoreRepository.search()       → 유사 청크 검색 (Application이 호출)
  chatClient.generate(chunks, question)→ GPT 답변 생성 (Infrastructure)
```

---

## 프로젝트 구조

```
rag-document/
├── CLAUDE.md
├── docs/
│   ├── step1-setup.md
│   ├── step2-rag-core.md
│   ├── step3-qa.md
│   ├── step4-slack-bot.md
│   ├── step5-document-parsing.md
│   ├── step6-extra-features.md
│   └── step7-deploy.md
├── docker-compose.yml
├── build.gradle.kts
├── src/main/kotlin/com/khj/ragdocument/
│   │
│   ├── document/                          # 📂 도메인: 문서
│   │   ├── domain/
│   │   │   ├── Document.kt                #   엔티티 (비즈니스 로직 포함)
│   │   │   ├── Chunk.kt                   #   값 객체
│   │   │   ├── SourceType.kt              #   소스 타입 enum
│   │   │   ├── DocumentRepository.kt      #   리포지토리 인터페이스
│   │   │   ├── ChunkRepository.kt         #   청크 리포지토리 인터페이스
│   │   │   └── ChunkingStrategy.kt        #   도메인 전략 객체 (청크 분할 순수 로직)
│   │   ├── application/
│   │   │   └── DocumentFacade.kt          #   유스케이스 조합 + Repository 호출
│   │   ├── presentation/
│   │   │   ├── DocumentController.kt      #   REST API (추후 웹 UI용)
│   │   │   └── dto/
│   │   │       ├── DocumentIngestRequest.kt   # 문서 입력 요청 DTO
│   │   │       └── DocumentIngestResponse.kt  # 문서 입력 응답 DTO
│   │   └── infrastructure/
│   │       ├── PdfParser.kt               #   PDFBox (5단계)
│   │       ├── DocxParser.kt              #   Apache POI (5단계)
│   │       └── WebPageParser.kt           #   Jsoup (5단계)
│   │
│   ├── embedding/                         # 📂 도메인: 임베딩
│   │   ├── domain/
│   │   │   └── EmbeddingClient.kt         #   클라이언트 인터페이스 (DIP)
│   │   └── infrastructure/
│   │       ├── OpenAiEmbeddingClient.kt   #   OpenAI 임베딩 API 구현체
│   │       └── dto/
│   │           └── EmbeddingResponse.kt   #   OpenAI 응답 DTO
│   │
│   ├── vectorstore/                       # 📂 도메인: 벡터 저장소
│   │   ├── domain/
│   │   │   └── VectorStoreRepository.kt   #   리포지토리 인터페이스 + VectorSearchResult
│   │   └── infrastructure/
│   │       └── MilvusVectorStore.kt       #   Milvus SDK 2.6 구현체
│   │
│   ├── rag/                               # 📂 도메인: RAG (질의응답)
│   │   ├── domain/
│   │   │   ├── Answer.kt                  #   답변 값 객체
│   │   │   └── ChatClient.kt             #   채팅 클라이언트 인터페이스 (DIP)
│   │   ├── application/
│   │   │   ├── RagFacade.kt               #   질문→검색→답변 유스케이스 조합
│   │   │   └── SearchResult.kt            #   검색 결과 조합 객체 (Facade 내부용)
│   │   └── infrastructure/
│   │       └── OpenAiChatClient.kt        #   OpenAI Chat API 구현체
│   │
│   ├── slack/                             # 📂 도메인: Slack 연동
│   │   ├── presentation/
│   │   │   └── SlackEventController.kt    #   Slack 이벤트 수신
│   │   └── infrastructure/
│   │       └── SlackApiClient.kt          #   Slack API 호출
│   │
│   └── global/                            # 📂 공통
│       ├── config/
│       │   └── WebClientConfig.kt         #   WebClient 빈 설정
│       ├── exception/                     #   공통 예외 처리
│       └── util/                          #   유틸리티
│
└── src/main/resources/
    └── application.yml
```