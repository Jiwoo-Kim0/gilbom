# 길봄 API 계약

- 문서 상태: 확정
- 적용 범위: 관리자 REST/SSE, 로봇 이미지 HTTPS API, 공통 오류
- API 버전: v1
- 기계 판독 계약: [`openapi-v1.yaml`](./openapi-v1.yaml)
- API 권한 계약: [`authorization-matrix.md`](./authorization-matrix.md)
- MQTT 계약: [`mqtt-contract-v1.0.pdf`](./mqtt-contract-v1.0.pdf)

## 1. 정본과 우선순위

API 계약은 다음 자료의 조합으로 구성한다.

| 계약 영역 | 정본 |
|---|---|
| 공통 성공·오류·Validation | [`common-api-response-and-errors.md`](./common-api-response-and-errors.md) |
| HTTP 요청·응답 schema가 등록된 operation | [`openapi-v1.yaml`](./openapi-v1.yaml) |
| 전체 REST/SSE endpoint와 필요 권한 | [`authorization-matrix.md`](./authorization-matrix.md) |
| 인증·세션·CSRF·MFA 의미 | [`admin-authentication-security.md`](./admin-authentication-security.md) |
| 통계 기간·집계·빈 데이터 의미 | [`statistics-api-contract.md`](./statistics-api-contract.md) |
| 보고서 상태·버전·승인 | [`report-status-contract.md`](./report-status-contract.md) |
| 아직 OpenAPI로 이전하지 않은 도메인 DTO 초안 | [기존 REST/SSE API 명세서](<../../docs/길봄_MQTT 통신_SWAGGER REST SSE_API 명세서.pdf>) |
| MQTT topic·payload·QoS·TTL | [`mqtt-contract-v1.0.pdf`](./mqtt-contract-v1.0.pdf) |

충돌 시 다음 순서로 판단한다.

1. 보안·권한·상태 정책은 주제별 Markdown 정본을 따른다.
2. OpenAPI에 등록된 operation의 HTTP method, path, parameter와 schema는 OpenAPI를 따른다.
3. API별 필요 권한과 객체 범위는 권한 매트릭스를 따른다.
4. OpenAPI에 아직 등록되지 않은 DTO는 기존 API 명세서를 v1 초안으로 사용한다.
5. 구현하는 API는 같은 변경에서 OpenAPI operation과 schema를 완성해야 하며, 구현 이후 PDF만을 런타임 계약으로 사용할 수 없다.

따라서 Day 1에는 전체 API의 경계·행위·권한과 공통 계약을 확정하고, 도메인 구현 작업은 해당 operation의 상세 schema를 OpenAPI로 이전한 뒤 시작한다.

## 2. 공통 HTTP 규칙

| 항목 | 계약 |
|---|---|
| Base path | `/api/v1` |
| 전송 | 운영 HTTPS만 허용 |
| JSON | `application/json; charset=UTF-8`, 필드명 `lowerCamelCase` |
| 식별자 | 내부·API 식별자는 UUID string, 외부 표시 코드는 별도 `robotCode`, `reportCode` 등 |
| 시각 | RFC 3339 date-time, 서버 저장·응답은 UTC `Z` |
| 날짜 | 시간 없는 업무 날짜만 `YYYY-MM-DD` |
| 좌표 | GeoJSON, `[longitude, latitude]` 순서 |
| 빈 배열 | `null`이 아니라 `[]` |
| 선택 필드 | 값이 없으면 schema가 `nullable`인 경우만 `null`, 그 외에는 필드 생략 |
| 성공 응답 | 공통 envelope 없이 실제 DTO |
| 오류 응답 | 공통 `ErrorResponse`와 `X-Request-Id` |

클라이언트가 보내지 않은 JSON 필드를 서버가 임의의 의미 있는 값으로 해석해야 할 때에는 OpenAPI에 default를 명시한다. 명시되지 않은 임의 필드는 허용하지 않는 것을 원칙으로 하며 보안 관련 DTO는 `additionalProperties: false`를 사용한다.

## 3. 인증 방식

| 호출 주체 | 인증 |
|---|---|
| 관리자 브라우저 | 서버 세션 쿠키 + TOTP MFA |
| 상태 변경 관리자 API | 서버 세션 + `X-CSRF-TOKEN` |
| 로봇 이미지 API | 기기별 `X-API-Key` |
| 로봇 MQTT | MQTT TLS + client별 credential·topic ACL |
| 공개 API | CSRF 발급, 최소 health·version 정보만 |

- 관리자 세션 쿠키와 CSRF 계약은 [`admin-authentication-security.md`](./admin-authentication-security.md)를 따른다.
- 공개하지 않은 endpoint는 기본적으로 인증을 요구한다.
- 기본 권한 부족은 `403`, 단건 객체가 사용자 범위 밖이면 존재 여부를 숨기기 위해 `404`를 반환한다.
- 프론트엔드는 MQTT Broker에 직접 접속하지 않는다.

## 4. 공통 헤더

| 헤더 | 방향 | 규칙 |
|---|---|---|
| `X-Request-Id` | 요청·응답 | 요청 UUID가 유효하면 사용하고 아니면 서버가 생성. 응답 본문·로그와 동일 |
| `X-CSRF-TOKEN` | 요청 | 관리자 상태 변경 전체와 로그인·로그아웃·MFA에 필수 |
| `Idempotency-Key` | 요청 | 로봇 명령과 보고서 생성에 필수인 UUID |
| `Retry-After` | 응답 | `429`, 일시적인 `503`에서 재시도 가능 초 |
| `Cache-Control: no-store` | 응답 | 로그인·MFA·CSRF·Secret 1회 노출·Presigned URL 응답 |
| `Content-Disposition` | 응답 | 파일 직접 다운로드를 제공하는 경우 안전한 파일명 사용 |

`Authorization`, Cookie, API Key, CSRF, Presigned URL을 응답 body나 로그로 반사하지 않는다.

## 5. HTTP 상태

| 상태 | 사용 |
|---:|---|
| `200` | 조회·수정 및 동기 action 성공 |
| `201` | 리소스 생성 |
| `202` | 비동기 작업 접수 또는 비밀번호 변경·MFA 등 인증 다음 단계 필요 |
| `204` | 응답 body 없는 성공 |
| `400` | JSON·타입·필수값·필드 Validation 오류 |
| `401` | 인증 실패·만료·MFA 미완료 |
| `403` | 권한·CSRF 실패 |
| `404` | 리소스 없음 또는 객체 범위 밖 |
| `409` | 중복, 멱등 키 충돌, 상태·버전 충돌 |
| `413` | 전역 요청 본문 또는 업로드 크기 상한 초과 |
| `415` | 지원하지 않는 Content-Type·이미지 형식 |
| `422` | 형식은 맞지만 업무 규칙 위반 |
| `429` | 인증·API 호출 한도 초과 |
| `500` | 예상하지 못한 내부 오류 |
| `503` | DB·S3·MQTT·LLM 등 필수 의존성 일시 장애 |

내부 예외, SQL, 저장소 경로, provider 응답 원문은 공통 오류로 변환하고 `requestId`만 외부 추적값으로 제공한다.

## 6. 목록·필터·정렬

- 페이지 번호는 0부터 시작한다.
- 기본 `page=0`, `size=20`, 최대 `size=100`으로 한다.
- `sort`는 `field,direction` 형식이며 API가 허용한 필드와 `asc|desc`만 받는다.
- 다중 enum filter는 쉼표로 구분한다. 예: `statuses=RUNNING,PAUSED`.
- 날짜 범위는 `from` 포함, `to` 미포함 `[from, to)`을 기본으로 한다. 다른 의미가 필요하면 operation에 명시한다.
- 목록 성공 응답은 다음 명시적 DTO를 사용한다.

```json
{
  "items": [],
  "page": 0,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0
}
```

- 객체 범위가 있는 목록·요약·지도는 권한 없는 행을 조회 후 제거하지 않고 DB query 단계에서 제한한다.
- 존재하지 않는 정렬 필드, 음수 page, 최대 size 초과는 `400 COMMON_INVALID_REQUEST`다.

## 7. 멱등성과 동시성

### 7.1 `Idempotency-Key`

- `POST /api/v1/robots/{robotId}/commands`와 `POST /api/v1/reports`에 필수다.
- 범위는 `인증 주체 + HTTP method + 정규화 path + key`다.
- 서버는 key, 요청 body hash, 최종 HTTP 상태와 안전한 응답을 24시간 보존한다.
- 같은 key와 같은 body 재시도는 최초 결과를 반환한다.
- 같은 key를 다른 body에 사용하면 `409 COMMON_CONFLICT`다.
- 실패 전 네트워크가 끊겨도 동일 key 재시도로 중복 명령·보고서를 만들지 않는다.

### 7.2 자연 멱등 키·버전

- 로봇 이미지 업로드는 `event_id`와 로봇 ID를 자연 멱등 키로 사용한다.
- MQTT 사건은 `event_id` 또는 `message_id`, 명령은 `command_id`를 사용한다.
- 보고서 수정·검토·승인·반려는 `expectedVersionNo`로 낙관적 잠금을 수행한다.
- 상태 전이와 멱등 결과를 같은 DB 트랜잭션에 저장한다.

## 8. 파일·이미지

- S3 bucket은 Private이고 DB에는 `file_id`, `object_key`, metadata만 저장한다.
- 로봇 대표 이미지는 JPEG만 허용하고 계약상 최대 500KB다.
- 확장자만 신뢰하지 않고 Content-Type, magic bytes, decode 가능 여부와 크기를 검사한다.
- 업로드 Presigned URL 기본 만료는 10분, 다운로드·미리보기 URL 기본 만료는 5분이다.
- URL은 단일 bucket·object key·HTTP method로 제한하고 목록·삭제 권한을 포함하지 않는다.
- Presigned URL 전체와 query string은 저장·로그하지 않는다.
- MQTT payload에는 이미지·Base64·S3 URL을 넣지 않고 `image_id`만 전달한다.
- 파일 조회 권한은 연결된 결함·보고서·프로필 등 원본 리소스 권한을 상속한다.

## 9. SSE

- Content-Type은 `text/event-stream`이다.
- 관리자 세션 쿠키를 사용하며 별도 브라우저 MQTT 연결을 만들지 않는다.
- 연결 시 권한을 검사하고 세션 만료·폐기 시 연결을 종료한다.
- 이벤트 발행 시에도 로봇·결함 객체 범위를 적용한다.
- 이벤트 payload에는 전체 entity가 아니라 화면 갱신에 필요한 ID, 상태, 시각과 안전한 요약만 넣는다.
- 브라우저는 EventSource 재연결을 사용한다. 유실 가능성이 있으므로 재연결 후 관련 REST 최신 상태를 다시 조회한다.
- 서버는 15초 간격 heartbeat comment를 보내고 연결·사용자별 상한을 둔다.
- SSE는 확정 저장소가 아니며 감사 로그나 업무 상태의 정본으로 사용하지 않는다.

## 10. API 버전과 변경

- v1의 breaking change는 금지한다.
- 선택 응답 필드·새 enum을 추가할 때 클라이언트가 알 수 없는 값을 견딜 수 있는지 검토한다.
- 필수 요청 필드 추가, 필드 의미 변경, 타입 변경, enum 제거, path 변경은 `/api/v2` 대상이다.
- 필드·operation 폐기는 최소 한 릴리스 전에 OpenAPI `deprecated: true`와 변경 문서로 알린다.
- DB 내부 이름을 바꾸더라도 API의 공개 코드·필드 의미를 임의로 변경하지 않는다.
- MQTT schema breaking change는 REST 버전과 별개로 MQTT `schema_version`을 올린다.

## 11. 계약 변경 절차

API를 구현하거나 변경하는 작업은 다음을 같은 변경에서 수행한다.

1. OpenAPI path, parameter, request·response schema와 오류 응답 갱신
2. 권한 매트릭스의 권한·객체 범위·CSRF·감사 여부 갱신
3. 새 오류 코드가 있으면 공통 오류 문서와 백엔드 enum·테스트 갱신
4. Controller·DTO·Validation 구현
5. 계약 테스트와 권한·실패 응답 테스트
6. Swagger UI 또는 생성 문서에서 예시·nullable·필수 필드 확인

구현된 endpoint가 OpenAPI에 없거나 OpenAPI와 실제 응답이 다르면 완료로 보지 않는다.

## 12. Day 1 완료와 후속 구현

Day 1에서는 다음 설계가 확정됐다.

- 전체 endpoint의 주체·권한·객체 범위
- 공통 HTTP·오류·페이지·시간·좌표·파일·SSE 규칙
- 인증·CSRF·MFA와 보고서 상태 operation의 기계 판독 계약
- 계약 정본 간 우선순위와 변경 절차

도메인 기능 구현 시에는 기존 PDF에 남은 각 DTO를 `openapi-v1.yaml`로 이전한다. 이는 설계 미정이 아니라 구현 operation별 계약 이관 작업이다.

