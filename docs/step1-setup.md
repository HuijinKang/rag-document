# 1단계: 프로젝트 세팅 가이드

## 개요

Spring Boot 프로젝트 생성, Docker 환경 구성, 패키지 구조 생성, 기본 설정까지 완료한다.

---

## 1. Spring Boot 프로젝트 생성

### 기본 설정

| 항목 | 값 |
|---|---|
| Language | Kotlin |
| Build Tool | Gradle (Kotlin DSL) |
| Spring Boot | 4.0.2 |
| Java | 21 |
| Group | com.khj.ragdocument |
| Artifact | rag-document |
| Package | com.khj.ragdocument |

### 의존성 (build.gradle.kts)

```kotlin
dependencies {
    // Spring
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // Kotlin
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Database
    runtimeOnly("org.postgresql:postgresql")

    // 문서 파싱
    implementation("org.apache.pdfbox:pdfbox:3.0.3")           // PDF
    implementation("org.apache.poi:poi-ooxml:5.3.0")           // DOCX
    implementation("org.jsoup:jsoup:1.18.1")                   // 웹 크롤링

    // OpenAI (HTTP 클라이언트로 직접 호출)
    implementation("org.springframework.boot:spring-boot-starter-webflux")  // WebClient

    // Milvus
    implementation("io.milvus:milvus-sdk-java:2.4.4")

    // Slack
    implementation("com.slack.api:bolt-spring-boot:1.42.0")
    implementation("com.slack.api:slack-api-client:1.42.0")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
```

> 버전은 개발 시점의 최신 안정 버전으로 조정한다.

---

## 2. Docker Compose 구성

### docker-compose.yml

```yaml
version: '3.8'

services:
  # --- Milvus 의존성 ---
  etcd:
    image: quay.io/coreos/etcd:v3.5.16
    environment:
      - ETCD_AUTO_COMPACTION_MODE=revision
      - ETCD_AUTO_COMPACTION_RETENTION=1000
      - ETCD_QUOTA_BACKEND_BYTES=4294967296
      - ETCD_SNAPSHOT_COUNT=50000
    volumes:
      - etcd_data:/etcd
    command: etcd -advertise-client-urls=http://127.0.0.1:2379 -listen-client-urls http://0.0.0.0:2379 --data-dir /etcd
    healthcheck:
      test: ["CMD", "etcdctl", "endpoint", "health"]
      interval: 30s
      timeout: 20s
      retries: 3

  minio:
    image: minio/minio:RELEASE.2024-09-22T00-33-43Z
    environment:
      MINIO_ACCESS_KEY: minioadmin
      MINIO_SECRET_KEY: minioadmin
    ports:
      - "9001:9001"    # Console
    volumes:
      - minio_data:/minio_data
    command: minio server /minio_data --console-address ":9001"
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:9000/minio/health/live"]
      interval: 30s
      timeout: 20s
      retries: 3

  # --- Milvus ---
  milvus:
    image: milvusdb/milvus:v2.4.13
    command: ["milvus", "run", "standalone"]
    environment:
      ETCD_ENDPOINTS: etcd:2379
      MINIO_ADDRESS: minio:9000
    ports:
      - "19530:19530"  # gRPC
      - "9091:9091"    # Health
    volumes:
      - milvus_data:/var/lib/milvus
    depends_on:
      etcd:
        condition: service_healthy
      minio:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:9091/healthz"]
      interval: 30s
      timeout: 20s
      retries: 3

  # --- PostgreSQL ---
  postgresql:
    image: postgres:16
    environment:
      POSTGRES_DB: ragdocument
      POSTGRES_USER: raguser
      POSTGRES_PASSWORD: ragpass
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data

volumes:
  etcd_data:
  minio_data:
  milvus_data:
  postgres_data:
```

### 실행 방법

```bash
# 전체 실행
docker-compose up -d

# 상태 확인
docker-compose ps

# 로그 확인
docker-compose logs -f milvus

# 종료
docker-compose down
```

### 연결 정보

| 서비스 | Host | Port | 비고 |
|---|---|---|---|
| Milvus | localhost | 19530 | gRPC |
| PostgreSQL | localhost | 5432 | DB: ragdocument, User: raguser, PW: ragpass |
| MinIO Console | localhost | 9001 | minioadmin / minioadmin |

---

## 3. 패키지 구조 생성

`src/main/kotlin/com/khj/ragdocument/` 하위에 아래 구조를 생성한다.

```
com/khj/ragdocument/
├── document/
│   ├── domain/
│   ├── application/
│   ├── presentation/
│   └── infrastructure/
├── embedding/
│   ├── domain/
│   └── infrastructure/
├── vectorstore/
│   ├── domain/
│   └── infrastructure/
├── rag/
│   ├── domain/
│   ├── application/
│   └── infrastructure/
├── slack/
│   ├── presentation/
│   └── infrastructure/
└── global/
    ├── config/
    ├── exception/
    └── util/
```

> 빈 패키지는 `.gitkeep` 파일을 넣어서 Git에 포함시킨다.

---

## 4. application.yml

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/ragdocument
    username: raguser
    password: ragpass
    driver-class-name: org.postgresql.Driver

  jpa:
    hibernate:
      ddl-auto: update
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
    show-sql: true

# OpenAI
openai:
  api-key: ${OPENAI_API_KEY}
  model: gpt-4o
  embedding-model: text-embedding-3-small

# Milvus
milvus:
  host: localhost
  port: 19530

# Slack
slack:
  bot-token: ${SLACK_BOT_TOKEN}
  signing-secret: ${SLACK_SIGNING_SECRET}
```

### 환경 변수

`.env` 파일 또는 시스템 환경 변수로 관리한다. **절대 Git에 커밋하지 않는다.**

```bash
export OPENAI_API_KEY=sk-xxxx
export SLACK_BOT_TOKEN=xoxb-xxxx
export SLACK_SIGNING_SECRET=xxxx
```

---

## 5. .gitignore

```gitignore
# Gradle
.gradle/
build/

# IDE
.idea/
*.iml

# Environment
.env
*.env

# OS
.DS_Store
Thumbs.db
```

---

## 6. 완료 기준

- [ ] Spring Boot 프로젝트가 정상 빌드된다 (`./gradlew build`)
- [ ] `docker-compose up -d` 로 Milvus, PostgreSQL이 정상 기동된다
- [ ] 애플리케이션이 정상 실행되고 PostgreSQL에 연결된다
- [ ] 패키지 구조가 문서대로 생성되어 있다