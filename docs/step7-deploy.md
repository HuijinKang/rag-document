# 7단계: 클라우드 배포 가이드

## 개요

로컬에서 개발한 rag-document를 클라우드 환경에 배포하여 24시간 운영 가능하게 만든다.

---

## 배포 아키텍처

```
[Slack] → [Cloud Server]
              ├── Spring Boot App (Docker)
              ├── Milvus (Docker)
              ├── PostgreSQL (Docker 또는 Managed DB)
              └── Nginx (리버스 프록시, HTTPS)
```

---

## 1. Dockerfile 작성

### 프로젝트 루트에 Dockerfile 생성

```dockerfile
# Build Stage
FROM gradle:8.10-jdk21 AS build
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
COPY src ./src
RUN gradle bootJar --no-daemon

# Run Stage
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar

ENV JAVA_OPTS="-Xms512m -Xmx1024m"
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

### 빌드 및 테스트

```bash
# 이미지 빌드
docker build -t rag-document:latest .

# 로컬 테스트
docker run -p 8080:8080 \
  -e OPENAI_API_KEY=sk-xxxx \
  -e SLACK_BOT_TOKEN=xoxb-xxxx \
  -e SLACK_SIGNING_SECRET=xxxx \
  rag-document:latest
```

---

## 2. docker-compose.prod.yml

운영용 Docker Compose. 로컬 개발용과 분리한다.

```yaml
version: '3.8'

services:
  app:
    build: .
    ports:
      - "8080:8080"
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgresql:5432/ragdocument
      SPRING_DATASOURCE_USERNAME: raguser
      SPRING_DATASOURCE_PASSWORD: ${DB_PASSWORD}
      OPENAI_API_KEY: ${OPENAI_API_KEY}
      SLACK_BOT_TOKEN: ${SLACK_BOT_TOKEN}
      SLACK_SIGNING_SECRET: ${SLACK_SIGNING_SECRET}
      MILVUS_HOST: milvus
      MILVUS_PORT: 19530
    depends_on:
      milvus:
        condition: service_healthy
      postgresql:
        condition: service_healthy
    restart: always

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
    restart: always

  minio:
    image: minio/minio:RELEASE.2024-09-22T00-33-43Z
    environment:
      MINIO_ACCESS_KEY: ${MINIO_ACCESS_KEY}
      MINIO_SECRET_KEY: ${MINIO_SECRET_KEY}
    volumes:
      - minio_data:/minio_data
    command: minio server /minio_data
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:9000/minio/health/live"]
      interval: 30s
      timeout: 20s
      retries: 3
    restart: always

  milvus:
    image: milvusdb/milvus:v2.4.13
    command: ["milvus", "run", "standalone"]
    environment:
      ETCD_ENDPOINTS: etcd:2379
      MINIO_ADDRESS: minio:9000
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
    restart: always

  postgresql:
    image: postgres:16
    environment:
      POSTGRES_DB: ragdocument
      POSTGRES_USER: raguser
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U raguser -d ragdocument"]
      interval: 10s
      timeout: 5s
      retries: 5
    restart: always

  nginx:
    image: nginx:latest
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./nginx/nginx.conf:/etc/nginx/nginx.conf
      - ./nginx/ssl:/etc/nginx/ssl
    depends_on:
      - app
    restart: always

volumes:
  etcd_data:
  minio_data:
  milvus_data:
  postgres_data:
```

---

## 3. Nginx 설정

### nginx/nginx.conf

```nginx
events {
    worker_connections 1024;
}

http {
    upstream app {
        server app:8080;
    }

    server {
        listen 80;
        server_name your-domain.com;

        # HTTP → HTTPS 리다이렉트 (SSL 설정 시)
        # return 301 https://$server_name$request_uri;

        location / {
            proxy_pass http://app;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
        }

        # Slack 이벤트 엔드포인트
        location /slack/ {
            proxy_pass http://app;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_read_timeout 5s;  # Slack 3초 제한 고려
        }
    }
}
```

---

## 4. 환경 변수 관리

### .env.prod (Git에 커밋하지 않는다)

```bash
# Database
DB_PASSWORD=strong-password-here

# OpenAI
OPENAI_API_KEY=sk-xxxx

# Slack
SLACK_BOT_TOKEN=xoxb-xxxx
SLACK_SIGNING_SECRET=xxxx

# MinIO
MINIO_ACCESS_KEY=minioadmin
MINIO_SECRET_KEY=strong-minio-password
```

### 실행

```bash
# 환경 변수 로드 후 실행
docker-compose -f docker-compose.prod.yml --env-file .env.prod up -d
```

---

## 5. CI/CD (GitHub Actions)

### .github/workflows/deploy.yml

```yaml
name: Deploy

on:
  push:
    branches: [main]

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Build
        run: ./gradlew bootJar --no-daemon

      - name: Build Docker Image
        run: docker build -t rag-document:latest .

      - name: Deploy to Server
        uses: appleboy/ssh-action@v1
        with:
          host: ${{ secrets.SERVER_HOST }}
          username: ${{ secrets.SERVER_USER }}
          key: ${{ secrets.SERVER_SSH_KEY }}
          script: |
            cd /opt/rag-document
            git pull origin main
            docker-compose -f docker-compose.prod.yml --env-file .env.prod down
            docker-compose -f docker-compose.prod.yml --env-file .env.prod up -d --build
```

### GitHub Secrets 설정

| Secret | 값 |
|---|---|
| SERVER_HOST | 서버 IP 또는 도메인 |
| SERVER_USER | SSH 사용자명 |
| SERVER_SSH_KEY | SSH 개인 키 |

---

## 6. 서버 최소 사양

Milvus가 메모리를 많이 사용하므로 주의.

| 항목 | 최소 | 권장 |
|---|---|---|
| CPU | 2 Core | 4 Core |
| RAM | 8 GB | 16 GB |
| Disk | 30 GB | 50 GB+ |
| OS | Ubuntu 22.04+ | Ubuntu 24.04 |

---

## 7. 배포 후 체크리스트

### Slack App 설정 변경

- Event Subscriptions Request URL을 **운영 도메인으로 변경**
    - `https://your-domain.com/slack/events`
- ngrok URL은 제거

### 헬스체크

```bash
# 앱 상태 확인
curl https://your-domain.com/actuator/health

# Docker 상태 확인
docker-compose -f docker-compose.prod.yml ps

# 로그 확인
docker-compose -f docker-compose.prod.yml logs -f app
```

### Spring Boot Actuator 설정

`application.yml`에 추가:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, info
  endpoint:
    health:
      show-details: always
```

---

## 8. 프로젝트 최종 구조

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
├── docker-compose.yml              # 로컬 개발용
├── docker-compose.prod.yml         # 운영 배포용
├── Dockerfile
├── .github/
│   └── workflows/
│       └── deploy.yml
├── nginx/
│   └── nginx.conf
├── .env.prod                       # Git 제외
├── .gitignore
├── build.gradle.kts
└── src/
```

---

## 완료 기준

- [ ] Docker 이미지가 정상 빌드된다
- [ ] docker-compose.prod.yml로 전체 서비스가 기동된다
- [ ] Slack 이벤트가 운영 서버로 정상 수신된다
- [ ] 외부에서 Slack 봇을 통해 문서 등록 / 질문 / 답변이 동작한다
- [ ] GitHub Actions로 main 브랜치 push 시 자동 배포된다
- [ ] 서버 재시작 후에도 데이터가 유지된다 (volume 마운트)