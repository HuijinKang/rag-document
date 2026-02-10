# RAG Document

AI 기반 문서 요약 & Q&A 시스템

사용자가 문서(PDF, DOCX, URL, 텍스트)를 전달하면 AI가 내용을 분석하고, 질문에 대해 문서 기반으로 정확하게 답변하는 RAG(Retrieval-Augmented Generation) 시스템입니다.

## 기술 스택

| 구분 | 기술 |
|------|------|
| 언어 | Kotlin |
| 프레임워크 | Spring Boot 4.0.2 (Java 21) |
| AI (생성) | OpenAI GPT-4o |
| AI (임베딩) | OpenAI text-embedding-3-small |
| 벡터 DB | Milvus 2.6 |
| RDB | PostgreSQL 16 |
| 문서 파싱 | PDFBox, Apache POI, Jsoup |
| 인터페이스 | Slack Bot |

## 아키텍처

```
[문서 입력] PDF / DOCX / URL / 텍스트
      ↓
[텍스트 추출 → 청크 분할 → 임베딩 생성]
      ↓
[Milvus 벡터 저장]
      ↓
[사용자 질문 → 유사도 검색 → GPT 답변 생성]
```

DDD 기반 도메인 패키지 분리 + Layered Architecture (Presentation → Application → Domain → Infrastructure)

## 시작하기

### 사전 요구사항

- Java 21
- Docker & Docker Compose

### 환경 변수 설정

`.env` 파일을 프로젝트 루트에 생성합니다.

```bash
export OPENAI_API_KEY=sk-xxxx
export SLACK_BOT_TOKEN=xoxb-xxxx
export SLACK_SIGNING_SECRET=xxxx
```

### 인프라 실행

```bash
docker-compose up -d
```

| 서비스 | Host | Port | 비고 |
|--------|------|------|------|
| Milvus | localhost | 19530 | gRPC |
| PostgreSQL | localhost | 5432 | DB: ragdocument / User: raguser / PW: ragpass |
| MinIO Console | localhost | 9001 | minioadmin / minioadmin |

### 빌드 & 실행

```bash
# 빌드
./gradlew build

# 실행
./gradlew bootRun
```

## 프로젝트 구조

```
src/main/kotlin/com/khj/ragdocument/
├── document/          # 문서 도메인 (업로드, 파싱, 청크 분할)
├── embedding/         # 임베딩 도메인 (텍스트 → 벡터 변환)
├── vectorstore/       # 벡터 저장소 도메인 (Milvus 연동)
├── rag/               # RAG 도메인 (질의응답)
├── slack/             # Slack 연동
└── global/            # 공통 설정, 예외, 유틸리티
```
