# 4단계: Slack Bot 연동 가이드

## 개요

Slack 봇을 통해 사용자가 문서를 전송하고, 질문하면 RAG 기반 답변을 받을 수 있도록 연동한다.
2~3단계에서 만든 REST API 기반 로직을 Slack 이벤트로 연결하는 단계이다.

---

## 기본 패키지 경로

```
src/main/kotlin/com/khj/ragdocument/
```

---

## 사전 준비: Slack App 생성

### 1. Slack App 생성

1. https://api.slack.com/apps 접속
2. "Create New App" → "From scratch"
3. App 이름: `rag-document` (자유)
4. Workspace 선택 → Create App

### 2. Bot Token Scopes 설정

OAuth & Permissions → Bot Token Scopes에 아래 권한 추가:

| Scope | 용도 |
|---|---|
| `app_mentions:read` | 봇 멘션 감지 |
| `chat:write` | 메시지 발송 |
| `files:read` | 파일(PDF 등) 다운로드 |
| `im:history` | DM 메시지 읽기 |
| `im:read` | DM 채널 정보 읽기 |
| `im:write` | DM 메시지 발송 |

### 3. Event Subscriptions 설정

1. Event Subscriptions → Enable Events: ON
2. Request URL: `https://{your-domain}/slack/events` (개발 시 ngrok 사용)
3. Subscribe to bot events:
   - `app_mention` (채널에서 봇 멘션)
   - `message.im` (DM 메시지)
   - `file_shared` (파일 공유)

### 4. 토큰 발급

- OAuth & Permissions → Install to Workspace
- **Bot User OAuth Token** (`xoxb-...`) 복사
- Basic Information → **Signing Secret** 복사

### 5. 환경 변수 설정

```bash
export SLACK_BOT_TOKEN=xoxb-xxxx
export SLACK_SIGNING_SECRET=xxxx
```

### 6. 로컬 개발 시 ngrok 사용

Slack은 이벤트를 외부 URL로 보내기 때문에, 로컬에서 테스트하려면 ngrok이 필요하다.

```bash
# ngrok 실행 (8080 포트)
ngrok http 8080

# 출력된 URL을 Slack Event Subscriptions의 Request URL에 등록
# 예: https://xxxx.ngrok-free.app/slack/events
```

---

## 구현 순서

### 1. Slack Presentation

**패키지**: `com.khj.ragdocument.slack.presentation`

#### SlackEventController.kt

```kotlin
@RestController
@RequestMapping("/slack")
class SlackEventController(
    private val documentFacade: DocumentFacade,
    private val ragFacade: RagFacade,
    private val slackApiClient: SlackApiClient,
) {

    /**
     * Slack Event API 엔드포인트
     * - URL 검증 (challenge)
     * - 메시지 이벤트 처리
     */
    @PostMapping("/events")
    fun handleEvent(@RequestBody payload: Map<String, Any>): ResponseEntity<Any> {

        // URL 검증 (Slack App 설정 시 최초 1회)
        if (payload["type"] == "url_verification") {
            return ResponseEntity.ok(mapOf("challenge" to payload["challenge"]))
        }

        // 이벤트 처리
        val event = payload["event"] as? Map<String, Any> ?: return ResponseEntity.ok("ok")
        val eventType = event["type"] as? String
        val text = event["text"] as? String ?: ""
        val channel = event["channel"] as? String ?: return ResponseEntity.ok("ok")
        val botId = event["bot_id"]  // 봇 자신의 메시지 무시

        if (botId != null) return ResponseEntity.ok("ok")

        // 비동기 처리 (Slack은 3초 내 응답 필요)
        Thread {
            try {
                handleMessage(event, text, channel)
            } catch (e: Exception) {
                slackApiClient.sendMessage(channel, "❌ 오류가 발생했습니다: ${e.message}")
            }
        }.start()

        return ResponseEntity.ok("ok")
    }

    private fun handleMessage(event: Map<String, Any>, text: String, channel: String) {
        // 파일이 포함된 메시지인지 확인
        val files = event["files"] as? List<Map<String, Any>>

        if (!files.isNullOrEmpty()) {
            handleFileUpload(files, channel)
        } else if (text.isNotBlank()) {
            handleQuestion(text, channel)
        }
    }

    private fun handleFileUpload(files: List<Map<String, Any>>, channel: String) {
        slackApiClient.sendMessage(channel, "📄 문서를 받았습니다. 분석 중...")

        files.forEach { file ->
            val fileName = file["name"] as? String ?: "unknown"
            val fileUrl = file["url_private_download"] as? String ?: return@forEach
            val mimeType = file["mimetype"] as? String ?: ""

            // 파일 다운로드 및 처리는 5단계에서 구현
            // 현재는 텍스트 기반만 지원
            slackApiClient.sendMessage(channel, "⚠️ 파일 처리는 아직 지원하지 않습니다. 텍스트를 직접 입력해주세요.")
        }
    }

    private fun handleQuestion(text: String, channel: String) {
        // 명령어 파싱
        val cleanText = text.replace(Regex("<@[A-Z0-9]+>"), "").trim()  // 멘션 제거

        when {
            cleanText.startsWith("/doc") -> {
                // 문서 등록: /doc 제목\n내용
                val parts = cleanText.removePrefix("/doc").trim().split("\n", limit = 2)
                val title = parts.getOrElse(0) { "제목 없음" }.trim()
                val content = parts.getOrElse(1) { "" }.trim()

                if (content.isBlank()) {
                    slackApiClient.sendMessage(channel, "📝 사용법: `/doc 제목`\\n`내용`")
                    return
                }

                slackApiClient.sendMessage(channel, "📄 문서를 분석 중...")
                val documentId = documentFacade.ingest(title, content, SourceType.TEXT)
                slackApiClient.sendMessage(channel, "✅ 문서 \"$title\" 분석 완료! (ID: $documentId)\n질문해주세요.")
            }

            cleanText.startsWith("/url") -> {
                // URL 등록: /url https://...
                val url = cleanText.removePrefix("/url").trim()
                slackApiClient.sendMessage(channel, "⚠️ URL 처리는 5단계에서 구현 예정입니다.")
            }

            else -> {
                // 일반 텍스트 → 질문으로 처리
                slackApiClient.sendMessage(channel, "🔍 문서에서 답변을 검색 중...")
                val answer = ragFacade.ask(cleanText)

                val sourceText = if (answer.sources.isNotEmpty()) {
                    "\n\n📚 *출처:*\n" + answer.sources.joinToString("\n") { source ->
                        "• ${source.documentTitle} (유사도: ${"%.2f".format(source.score)})"
                    }
                } else ""

                slackApiClient.sendMessage(channel, answer.content + sourceText)
            }
        }
    }
}
```

---

### 2. Slack Infrastructure

**패키지**: `com.khj.ragdocument.slack.infrastructure`

#### SlackApiClient.kt

```kotlin
@Component
class SlackApiClient(
    @Value("\${slack.bot-token}") private val botToken: String,
) {
    private val slack = Slack.getInstance()

    fun sendMessage(channel: String, text: String) {
        slack.methods(botToken).chatPostMessage { req ->
            req.channel(channel)
                .text(text)
                .mrkdwn(true)
        }
    }

    fun downloadFile(fileUrl: String): ByteArray {
        val response = slack.methods(botToken)
        // Slack 파일 다운로드는 Authorization 헤더 필요
        val connection = java.net.URL(fileUrl).openConnection()
        connection.setRequestProperty("Authorization", "Bearer $botToken")
        return connection.getInputStream().readBytes()
    }
}
```

---

### 3. Slack Config

**패키지**: `com.khj.ragdocument.global.config`

#### SlackConfig.kt

```kotlin
@Configuration
class SlackConfig {
    // Slack Bolt 사용 시 추가 설정
    // 현재는 REST 기반으로 직접 처리하므로 별도 설정 불필요
    // Bolt 전환 시 여기에 App, AppConfig 빈 등록
}
```

---

## 사용 흐름

### 문서 등록

```
사용자: /doc 회사 연차 정책
       직원은 입사 1년 후 15일의 연차를 부여받습니다.
       미사용 연차는 이월되지 않습니다.

봇:    📄 문서를 분석 중...
봇:    ✅ 문서 "회사 연차 정책" 분석 완료! (ID: 1)
       질문해주세요.
```

### 질문

```
사용자: 연차는 몇 일이야?

봇:    🔍 문서에서 답변을 검색 중...
봇:    입사 1년 후 15일의 연차가 부여됩니다.

       📚 출처:
       • 회사 연차 정책 (유사도: 0.92)
```

---

## 테스트 방법

### 1. ngrok 실행

```bash
ngrok http 8080
```

### 2. Slack App 설정

- Event Subscriptions Request URL에 ngrok URL 등록
  - `https://xxxx.ngrok-free.app/slack/events`

### 3. 애플리케이션 실행

```bash
docker-compose up -d
./gradlew bootRun
```

### 4. Slack에서 테스트

- 봇을 채널에 초대하거나 DM으로 메시지 전송
- `/doc 제목\n내용` 형태로 문서 등록
- 일반 텍스트로 질문

---

## 완료 기준

- [ ] Slack에서 봇에게 텍스트를 보내면 문서로 등록된다
- [ ] Slack에서 질문을 보내면 문서 기반 답변이 반환된다
- [ ] 답변에 출처 정보가 포함된다
- [ ] 봇 자신의 메시지에 반응하지 않는다 (무한루프 방지)
- [ ] 3초 내 Slack에 응답하고, 실제 처리는 비동기로 진행된다

---

## 주의사항

- Slack은 이벤트 전송 후 **3초 내 200 응답**을 기대한다. 초과 시 재전송함 → 비동기 처리 필수
- 같은 이벤트가 중복 전송될 수 있다 → 이벤트 ID 기반 중복 처리 고려
- 로컬 개발 시 ngrok URL이 변경되면 Slack App 설정도 업데이트해야 한다
- 파일 업로드 처리는 5단계에서 구현한다