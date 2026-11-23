# LLM Client·정책 게이트

- 기준일: 2026-08-05
- 적용 범위: 결함 보고서 초안 생성
- 구현 기준: `com.gilbom.llm`, `com.gilbom.report.generation`

## 1. 설계 원칙

보고서 상태·조회·수정 서비스는 외부 LLM에 의존하지 않는다. 외부 호출은 `LlmClient` 포트 뒤로 격리하고, 정책 조건을 통과한 환경에서만 `GmsLlmClient`를 생성한다. 정책이 미충족이거나 기능이 꺼져 있으면 애플리케이션은 정상 기동하며 `DisabledLlmClient`와 `TemplateReportGenerator`를 사용한다.

```text
환경설정
  └─ LlmPolicyGate
       ├─ 통과 → GmsLlmClient → gms.ssafy.io:443
       └─ 차단 → DisabledLlmClient
                         └─ ReportLlmGenerationPipeline
                              └─ TEMPLATE fallback

테스트 설정 → FakeLlmClient
```

이 구조로 LLM 장애와 운영 정책 변경이 일반 보고서 조회·수정 API의 가용성에 전파되지 않는다.

## 2. 서버 고정값

| 항목 | 허용값 |
|---|---|
| Provider | `GMS` |
| Base URL | `https://gms.ssafy.io/gmsapi/api.openai.com/v1` |
| 호출 경로 | `POST /chat/completions` |
| Outbound | `gms.ssafy.io:443` |
| 보고서 모델 | `gpt-5.4-mini`, `gpt-5.4` |
| Prompt 버전 | 운영 `report-v3`, 롤백 호환 `report-v1`, `report-v2` |
| 최대 출력 | 1,500 tokens |
| 최대 입력 | 보수적 비용 예약 기준 8,000 tokens |
| 동시 호출 | 인스턴스당 기본 5, 허용 범위 1~20 |
| Redirect | 허용하지 않음 |
| Provider 저장 요청 | `store=false` |
| 현재 이미지 수 | 기간 내 대표 결함 이미지 최대 1장 |
| 이미지 전송 형식 | 메모리 JPEG bytes → Base64 data URL |

Base URL 검사는 HTTPS scheme, 정확한 host·443 port·path를 모두 비교하고 user info, query, fragment를 거부한다. DNS·방화벽 계층의 실제 egress allowlist를 애플리케이션이 대신할 수 없으므로, 운영자가 적용을 확인한 뒤 `GILBOM_LLM_OUTBOUND_ALLOWLIST_APPLIED=true`로 승인해야 한다.

## 3. 정책 판정

`GILBOM_LLM_ENABLED=false`이면 `FEATURE_DISABLED`로 판정한다. `true`이면 아래 조건을 모두 만족해야 외부 Client가 활성화된다.

| 조건 | 위반 코드 |
|---|---|
| API Key 존재 | `API_KEY_MISSING` |
| Provider가 `GMS` | `PROVIDER_NOT_ALLOWED` |
| 정확한 GMS Base URL | `BASE_URL_NOT_ALLOWED` |
| 보고서 허용 모델 | `MODEL_NOT_ALLOWED` |
| 학습 제외 승인 | `TRAINING_EXCLUSION_NOT_APPROVED` |
| 데이터 보존 승인 | `DATA_RETENTION_NOT_APPROVED` |
| 일별 요청 한도 > 0 | `DAILY_REQUEST_LIMIT_MISSING` |
| 일별 크레딧 Budget > 0 | `DAILY_BUDGET_MISSING` |
| Outbound allowlist 적용 | `OUTBOUND_ALLOWLIST_NOT_APPLIED` |
| Prompt 버전 입력 | `PROMPT_VERSION_MISSING` |
| Prompt 버전 allowlist | `PROMPT_VERSION_NOT_ALLOWED` |
| 전체 정책 승인 | `POLICY_NOT_APPROVED` |
| Connect `1ms..10s`, Read `1ms..60s` | `TIMEOUT_INVALID` |
| 최대 출력 `1..1500` | `MAX_OUTPUT_TOKENS_INVALID` |
| 동시 호출 상한 `1..20` | `MAX_CONCURRENT_REQUESTS_INVALID` |

위반 내용에는 API Key를 넣지 않는다. 설정 실패는 외부 호출 차단 사유이지 애플리케이션 시작 실패 사유가 아니다.

## 4. Client 책임

- `LlmClient`: 외부 생성 포트. 요청에는 구조화된 입력 JSON만 받는다.
- `GmsLlmClient`: 서버에 고정된 endpoint, model, System Prompt, 출력 상한과
  `json_schema` strict Structured Outputs 계약으로 GMS를 호출한다. 설정한 승인 모델
  별칭과 동일한 응답 모델 또는 그 별칭의 `YYYY-MM-DD` 날짜 snapshot만 허용한다.
- `FakeLlmClient`: 테스트 source set에서 응답·오류·가용성을 결정적으로 제어한다.
- `DisabledLlmClient`: 외부 요청을 만들지 않고 직접 호출 시 `LLM_UNAVAILABLE`을 반환한다.
- `TemplateReportGenerator`: 외부 통신 없이 같은 입력으로 항상 같은 `TEMPLATE` 보고서 본문을 만든다.
- `ReportGenerationModeResolver`: LLM이 비활성화된 경우에만 `LLM` 요청을 `TEMPLATE`로 낮추고 `MANUAL`, `TEMPLATE` 요청은 보존한다.
- `DefectReportImageService`: 사용자 ID와 기간으로 객체 접근 범위를 적용해 대표 결함 상위 N건의 관측 이미지 bytes를 `List<DefectReportImage>`로 제공한다. 기본·현재 호출값은 1, 서비스 계약 상한은 3이다.
- `ReportLlmRequestFactory`: 이미지 하위 gate를 확인한 뒤 서비스를 호출하고, 결함 근거 정합성을 재검사하며 결과가 없으면 텍스트 전용 요청을 만든다.
- `ReportLlmInput`: 기간, 집계, 상태·유형·위험도 분포, 최소 결함 근거만 표현하는
  타입 기반 allowlist다. 사용자 ID는 orchestration에만 쓰고 이 DTO에는 포함하지 않는다.
- `LlmOutboundInputGuard`: Client 호출 직전 key·문자열·크기·깊이를 다시 검사해
  credential, 세션·쿠키, 이메일·전화번호, URL·경로, 임의 bean과 byte 배열을 거부한다.
- `ReportLlmResultValidator`: JSON Schema 형태, 추가 필드 금지, 길이·enum과 함께
  기간·통계·결함 식별자·모델·Prompt 버전을 서버 원본과 대조한다.
- `ReportLlmGenerationPipeline`: 정책, 동시성, 사용자별 일일 요청 수, 전체 일일
  credit을 통과한 호출만 수행하고 안전한 구조 결과 또는 `TEMPLATE` fallback을 반환한다.

사용자 API DTO에는 model, base URL, System Prompt를 두지 않는다. Provider가 반환한
모델 식별자가 요청 모델과 다르거나 응답이 비어 있으면 `LLM_RESPONSE_INVALID`,
연결·timeout·HTTP 오류이면 `LLM_UNAVAILABLE`로 분류하되, 보고서 파이프라인은
외부 실패 원문을 전달하지 않고 템플릿 결과와 고정된 fallback 코드를 반환한다.

## 5. 호출·검증 순서

1. `ReportLlmInput` 생성자에서 기간, 음수 통계, 분포 합계, 중복 결함을 검사한다.
2. `ReportLlmInputPreparer`가 DTO를 고정 JSON slot으로 바꾸고 outbound 금지 패턴과
   128KiB 상한을 검사한다.
3. 이미지 gate가 승인된 경우에만 `actorUserId`로 이미지 서비스를 호출하고 반환된
   `defectId`, `incidentCode`, `defectType`, `riskLevel`이 텍스트 allowlist와
   일치하는지 확인한다.
4. 인스턴스 semaphore를 얻은 뒤 PostgreSQL ledger에서 사용자별 일일 요청 수와 전체
   일일 credit을 한 UTC 날짜 advisory lock 아래 원자적으로 예약한다.
5. GMS를 호출한다. provider에는
   `backend/src/main/resources/llm/report-structured-output.schema.json`을
   `response_format.type=json_schema`, `strict=true`로 전달한다. `429`, `5xx`,
   network timeout만 100ms 기반 backoff와 jitter 후 최대 한 번 재시도한다.
   재시도도 별도 quota·credit 사용량으로 센다.
6. `4xx`와 JSON·Schema 오류는 재시도하지 않는다.
7. 응답은 최대 100,000자, 정확한 필드 집합, JSON 타입·enum·개수, 원본 통계·기간·
   결함 identity 일치, 자연어에 포함된 결함 ID·사건 코드의 원본 일치와
   HTML·script·실행 command 금지를 모두 통과해야 한다.
8. 검증된 구조만 Markdown text로 렌더링하며, 식별자는 서버 원본으로 표시하고
   결함 유형·위험도·처리 상태·조치 우선순위 enum은 한국어 의미로 치환한다.
   provider raw text는 보고서 본문이나
   usage ledger에 저장하지 않는다.
9. 어느 외부 단계가 실패해도 `TemplateReportGenerator`로 낮추고
   `ReportGenerationFallbackReason`의 고정 코드만 상위 생성 서비스에 전달한다.

## 6. 이미지 바이트 입력 계약

이미지는 사용자 생성 API body에서 받지 않는다. 서버가 보고서 기간을 기준으로
`findReportImages(actorUserId, from, to, 1)`을 호출하며, 반환형은 한 장이어도
`List<DefectReportImage>`다. 선정 기준은 기간 내 대표 결함 상위 N건과 각 결함의
대표 관측 이미지 한 장이다.

허용되는 이미지 입력은 다음 조건을 모두 만족해야 한다.

- `GILBOM_LLM_IMAGE_INPUT_ENABLED=true`
- `GILBOM_LLM_IMAGE_DEIDENTIFICATION_APPROVED=true`
- `image/jpeg`, JPEG magic, 1~512,000 bytes, 양수 width·height
- 얼굴·차량번호·사람 식별 단서와 EXIF/GPS metadata 제거가 검증됨
- `defectId`, `incidentCode`, `defectType`, `riskLevel`이 같은 요청의 텍스트
  allowlist와 일치함

GMS Chat Completions adapter는 호출 직전에만 bytes를 Base64 data URL로 바꾼다.
Presigned URL, bucket, object key, 로컬 파일 경로를 만들거나 전달하지 않는다.
Base64와 원본 bytes는 보고서, 버전, 멱등 저장소, 감사·애플리케이션 로그에 저장하지
않는다. 서비스가 없거나 빈 목록을 반환하거나 이미지 gate가 닫혀 있으면 외부 요청은
기존 문자열 content를 사용해 텍스트만으로 생성한다.

## 7. 비밀정보·로그 규칙

- API Key getter를 공개하지 않고 `toString()`은 항상 `[REDACTED]`로 표시한다.
- 실패 예외에는 Provider 응답 원문과 원래 HTTP 예외를 보존하지 않는다.
- `Authorization` 헤더, Prompt, 사용자 입력, 응답 원문을 애플리케이션 로그에 기록하지 않는다.
- Actuator 노출은 `health,info,prometheus`로 제한하고 `env`, `configprops`의 값 표시를 `never`로
  고정한다. `prometheus`는 Nginx가 프록시하지 않는 내부망 전용 수집 경로이며, LLM Prompt·응답·
  Authorization을 실을 수 있는 metric은 등록하지 않는다.
- 관측 정보가 필요할 때는 모델 식별자, Prompt 버전, token usage, 성공 여부 같은 비본문 메타데이터만 사용한다.
- 이미지 관측 정보는 전송 장수와 성공 여부만 기록할 수 있다. bytes, Base64, data URL, `defectId`와 `incidentCode`의 조합을 로그에 남기지 않는다.
- `request_control.llm_invocation_usage`에는 UTC 날짜, 사용자 UUID, provider·model,
  token·credit, 성공 여부와 고정 실패 코드만 기록한다. Prompt, 응답, 이미지,
  Authorization, URL과 내부 예외 메시지를 표현하는 column 자체가 없다.

## 8. 비용·Rate Limit

`GILBOM_LLM_DAILY_REQUEST_LIMIT`는 사용자 한 명이 UTC 하루 동안 수행할 수 있는
실제 provider 호출 횟수다. 최초 호출과 재시도, 성공·실패를 모두 센다.
`GILBOM_LLM_DAILY_BUDGET`는 모든 사용자·허용 모델을 합한 UTC 일일 hard gate다.

호출 전에는 승인된 최대 입력 8,000 token과 설정된 최대 출력 token을 모델 단가에
적용해 보수적으로 예약한다. 성공하고 provider usage가 있으면 실제 계산액으로 낮추며,
실패 또는 usage 미제공이면 실패 호출도 비용에 포함한다는 정책에 따라 예약액을
유지한다. 날짜별 advisory transaction lock으로 동시 요청이 budget을 함께 통과하는
race를 막는다. 애플리케이션 한도와 별개로 GMS·인프라 hard cap도 적용한다.

## 9. 테스트 기준

- 기본 설정과 불완전한 활성 설정에서 컨텍스트가 기동하고 `DisabledLlmClient`가 선택된다.
- 정책 조건이 모두 충족될 때만 `GmsLlmClient`가 선택된다.
- 테스트의 `FakeLlmClient`가 실제 Client를 대체한다.
- 정확한 endpoint·서버 model·System Prompt·출력 상한·`store=false`만 전송한다.
- Provider 오류가 API Key나 응답 원문을 예외에 남기지 않는다.
- 미승인 URL·model·Prompt·timeout·Budget을 각각 차단한다.
- LLM 비활성 시 템플릿 생성이 가능하고 기존 보고서 회귀 테스트가 모두 통과한다.
- 이미지 비식별화 미승인·서비스 부재·빈 목록은 이미지 전송 없이 텍스트 생성으로 이어진다.
- 승인된 JPEG는 한 장만 Base64 data URL로 전달되고 Presigned URL·저장소 경로는 요청에 없다.
- credential·개인정보·URL·임의 필드와 128KiB 초과 입력은 외부 Client 호출 전에
  거부된다.
- 알 수 없는 결과 필드, 변조 통계, 미등록 결함 ID, 기간·모델·Prompt 불일치와
  HTML·script·명령 출력은 저장 가능한 `ReportContent`로 변환되지 않는다.
- `429`, `5xx`, network timeout만 총 두 번까지 호출되고 `4xx`, Schema 오류는 한 번
  호출 후 템플릿으로 낮아진다.
- 사용자 일일 한도와 전체 일일 budget은 DB 경쟁 상황에서 한 요청만 통과시킨다.
