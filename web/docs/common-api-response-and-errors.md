# 공통 API 응답 및 예외·Validation 계약

- 문서 상태: 확정
- 적용 범위: 길봄 REST API
- OpenAPI 정본: [`openapi-v1.yaml`](./openapi-v1.yaml)
- 구현 기준: Spring Boot 3.5, Java 21

## 1. 성공 응답

성공 응답은 공통 `data` 또는 `success` envelope로 감싸지 않고 API DTO를 그대로 반환한다.

```json
{
  "robotId": "c36b2db6-6f33-4fc8-8575-929753fccd40",
  "robotCode": "RBT-001",
  "presenceStatus": "ONLINE"
}
```

HTTP 상태와 응답 형식은 다음 원칙을 따른다.

| 상황 | 응답 |
|---|---|
| 조회·수정 성공 | `200 OK`와 해당 DTO |
| 신규 리소스 생성 | `201 Created`와 생성 DTO, 필요하면 `Location` 헤더 |
| 비동기 명령·작업 접수 | `202 Accepted`와 접수 결과 DTO |
| 응답 본문 없는 완료 | `204 No Content` |
| 목록·페이지 조회 | API별 `items`와 페이지 메타데이터를 갖는 명시적 페이지 DTO |
| 파일 다운로드 | 파일의 실제 Content-Type과 바이트 스트림 |
| SSE | `text/event-stream` |

공통 envelope를 사용하지 않는 이유는 다음과 같다.

- HTTP 상태와 `success`, `status` 필드가 중복되지 않는다.
- OpenAPI의 성공 응답 타입이 실제 DTO와 일치한다.
- `204`, 파일 다운로드, SSE에도 예외 규칙 없이 적용할 수 있다.
- 기존 API 명세의 로그인·로봇 상태·명령 응답 예시와 일치한다.

페이지 응답은 다음과 같이 해당 API의 계약으로 명시한다.

```json
{
  "content": [],
  "page": 0,
  "size": 20,
  "totalElements": 135,
  "totalPages": 7
}
```

## 2. 공통 오류 응답

모든 REST 오류는 다음 형식을 사용한다. `details`는 오류가 없을 때에도 `null` 대신 빈 배열 `[]`로 반환한다.

```json
{
  "timestamp": "2026-07-25T10:30:00Z",
  "status": 400,
  "code": "COMMON_VALIDATION_FAILED",
  "message": "요청 값 검증에 실패했습니다.",
  "path": "/api/v1/reports",
  "requestId": "18c88a89-7d89-4c17-97fa-a6e2253614d9",
  "details": [
    {
      "field": "title",
      "constraint": "NotBlank",
      "rejectedValue": "",
      "reason": "제목은 필수입니다."
    }
  ]
}
```

| 필드 | 타입 | 필수 | 규칙 |
|---|---|---|---|
| `timestamp` | date-time | O | 서버가 오류 응답을 생성한 UTC 시각 |
| `status` | integer | O | 실제 HTTP 상태와 같은 값 |
| `code` | string | O | 안정적인 애플리케이션 오류 코드 |
| `message` | string | O | 사용자에게 노출 가능한 전체 오류 요약 |
| `path` | string | O | query string을 제외한 요청 URI |
| `requestId` | UUID string | O | 요청·응답 헤더 `X-Request-Id` 및 서버 로그와 동일한 값 |
| `details` | array | O | Validation 상세. 해당 사항이 없으면 `[]` |

서버 내부 예외 메시지, SQL, 스택 트레이스, 저장소 경로, 인증정보는 응답에 포함하지 않는다. 예상하지 못한 오류는 `COMMON_INTERNAL_ERROR`의 고정 메시지를 반환한다. 운영 로그에도 예외 객체를 통째로 기록하지 않고 `requestId`와 `DATABASE`, `IO`, `SECURITY`, `APPLICATION` 중 하나의 안전한 분류만 남긴다.

## 3. 애플리케이션 오류 코드

오류 코드는 `{DOMAIN}_{REASON}` 형태의 `UPPER_SNAKE_CASE`를 사용한다.

- HTTP 상태 번호를 이름에 넣지 않는다.
- 화면 표시 문구가 아닌 클라이언트가 분기할 수 있는 안정적인 값으로 정의한다.
- 공개된 코드의 의미를 바꾸거나 다른 상황에 재사용하지 않는다.
- 새로운 도메인 오류는 가장 가까운 공통 코드로 뭉개지 말고 구체적인 코드를 추가한다.
- HTTP 상태와 기본 안전 메시지는 백엔드 `ErrorCode` enum과 OpenAPI에 함께 등록한다.

### 3.1 공통·인증 코드

| 오류 코드 | HTTP | 의미 |
|---|---:|---|
| `COMMON_INVALID_REQUEST` | 400 | 필수 파라미터·헤더 누락 또는 타입 불일치 |
| `COMMON_MALFORMED_JSON` | 400 | JSON 파싱 실패 |
| `COMMON_VALIDATION_FAILED` | 400 | Bean Validation 필드·파라미터 검증 실패 |
| `AUTH_UNAUTHENTICATED` | 401 | 로그인 세션 또는 로봇 인증 없음 |
| `AUTH_FORBIDDEN` | 403 | RBAC 권한 부족 또는 CSRF 검증 실패 |
| `AUTH_PASSWORD_REUSE_NOT_ALLOWED` | 422 | 새 비밀번호가 현재 비밀번호와 동일 |
| `AUTH_MFA_UNAVAILABLE` | 503 | TOTP 암호화·검증 기반을 안전하게 사용할 수 없음 |
| `COMMON_RESOURCE_NOT_FOUND` | 404 | 도메인을 특정할 수 없는 리소스 없음 |
| `COMMON_METHOD_NOT_ALLOWED` | 405 | 지원하지 않는 HTTP 메서드 |
| `COMMON_CONFLICT` | 409 | 도메인을 특정할 수 없는 중복·상태 충돌 |
| `IDEMPOTENCY_KEY_REUSED` | 409 | 같은 멱등 key를 다른 요청 fingerprint에 재사용 |
| `COMMON_PAYLOAD_TOO_LARGE` | 413 | 전역 요청 본문 크기 상한 초과 |
| `COMMON_UNSUPPORTED_MEDIA_TYPE` | 415 | 일반 API에서 지원하지 않는 Content-Type |
| `COMMON_BUSINESS_RULE_VIOLATION` | 422 | 도메인을 특정할 수 없는 업무 규칙 위반 |
| `COMMON_RATE_LIMIT_EXCEEDED` | 429 | 요청 한도 초과 |
| `COMMON_INTERNAL_ERROR` | 500 | 예상하지 못한 서버 오류 |
| `COMMON_SERVICE_UNAVAILABLE` | 503 | 의존 대상을 특정할 수 없는 서비스 장애 |

### 3.2 도메인 코드

| 오류 코드 | HTTP | 의미 |
|---|---:|---|
| `ROBOT_NOT_FOUND` | 404 | 로봇 없음 |
| `ROBOT_CREDENTIAL_NOT_FOUND` | 404 | 지정한 로봇의 Credential 없음 |
| `ROBOT_CREDENTIAL_CONFLICT` | 409 | DB에 활성 Credential이 없지만 Broker에 같은 MQTT 계정이 이미 존재함 |
| `ROBOT_CREDENTIAL_SECRET_ALREADY_ISSUED` | 409 | 완료된 발급·회전 멱등 요청의 one-time Secret 재조회 시도 |
| `ROBOT_CREDENTIAL_STATE_CONFLICT` | 409 | ACTIVE가 아닌 Credential의 폐기 등 현재 상태에서 허용되지 않는 변경 |
| `PATROL_AREA_CODE_CONFLICT` | 409 | 이미 등록된 순찰 구역 코드 |
| `PATROL_AREA_STATE_CONFLICT` | 409 | 현재 순찰 구역 상태에서 허용되지 않는 변경·상태 전이 |
| `PATROL_AREA_IN_USE` | 409 | CREATED·READY·RUNNING·PAUSED Mission이 사용하는 구역의 변경·보관 시도 |
| `PATROL_AREA_GEOMETRY_INVALID` | 422 | 순찰 구역 경계가 유효한 Polygon/MultiPolygon 또는 4326 좌표 범위를 충족하지 않음 |
| `PATROL_AREA_ROUTE_OUTSIDE_BOUNDARY` | 422 | 변경한 순찰 구역 경계가 기존 ACTIVE·DRAFT Route를 포함하지 않음 |
| `PATROL_ROUTE_VERSION_CONFLICT` | 409 | 동일 순찰 구역·경로 코드의 버전 생성 경합 |
| `PATROL_ROUTE_GEOMETRY_INVALID` | 422 | 순찰 경로가 유효한 LineString/MultiLineString 또는 4326 좌표 범위를 충족하지 않음 |
| `PATROL_ROUTE_OUTSIDE_AREA` | 422 | 순찰 경로가 선택한 순찰 구역 경계 밖에 있음 |
| `DEFECT_NOT_FOUND` | 404 | 결함 없음 |
| `COMMAND_NOT_FOUND` | 404 | 명령 없음 |
| `REPORT_NOT_FOUND` | 404 | 보고서 없음 |
| `REPORT_ACCESS_DENIED` | 403 | 인증 사용자의 보고서 기능 권한 부족 |
| `IMAGE_NOT_FOUND` | 404 | 이미지 없음 |
| `COMMAND_STATE_CONFLICT` | 409 | 현재 명령·로봇 상태와 요청 충돌 |
| `MQTT_MESSAGE_EXPIRED` | 422 | Topic별 기준시각과 TTL을 초과한 수신 메시지 |
| `MQTT_TIMESTAMP_IN_FUTURE` | 422 | 기준시각이 서버 수신시각보다 허용 오차 이상 미래임 |
| `MQTT_REPLAY_REJECTED` | 422 | 종료·이전 Boot 메시지, 새 Boot의 `seq!=1` 또는 증가하지 않는 `seq` |
| `MQTT_SEQUENCE_CONFLICT` | 422 | 같은 `(robot_id, boot_id, seq)`를 다른 Topic·메시지가 이미 사용함 |
| `REPORT_STATE_CONFLICT` | 409 | 보고서 상태 전이 또는 버전 경합 |
| `REPORT_VALIDATION_FAILED` | 422 | 보고서 기간·본문·통계·사유 업무 규칙 위반 |
| `IMAGE_TOO_LARGE` | 413 | 이미지 크기 상한 초과 |
| `IMAGE_UNSUPPORTED_MEDIA_TYPE` | 415 | Content-Type은 유효하지만 지원하지 않는 이미지 형식 |
| `STATISTICS_PERIOD_INVALID` | 422 | 통계 기간이 역전·동일하거나 최대 366일을 초과함 |
| `DB_UNAVAILABLE` | 503 | 데이터베이스 장애 |
| `S3_UNAVAILABLE` | 503 | 파일 저장소 장애 |
| `MQTT_CREDENTIAL_PROVISIONING_UNAVAILABLE` | 503 | Mosquitto Credential 반영·인증 검증 장애 |
| `MQTT_CREDENTIAL_REGISTRY_DRIFT` | 503 | DB의 활성 hash와 Mosquitto passwd hash 불일치 |
| `MQTT_UNAVAILABLE` | 503 | MQTT Broker 또는 Adapter 장애 |
| `LLM_UNAVAILABLE` | 503 | LLM 비활성, timeout, 연결 또는 provider 장애 |
| `LLM_RESPONSE_INVALID` | 502 | LLM 응답 JSON Schema 또는 원본 정합성 검증 실패 |

`ERROR_400`, `BAD_REQUEST_ERROR`, `INVALID`처럼 HTTP 상태나 모호한 단어만으로 만든 코드는 사용하지 않는다.

## 4. Validation 상세

Validation 오류 하나는 다음 필드를 사용한다.

| 필드 | 타입 | 필수 | 규칙 |
|---|---|---|---|
| `field` | string | O | `title`, `items[0].name`, `location.latitude`와 같은 입력 경로 |
| `constraint` | string | X | `NotBlank`, `Size`, `Min`, `Type`, `Required`와 같은 제약 식별자 |
| `rejectedValue` | string/null | X | 안전하게 문자열화한 거부 값 |
| `reason` | string | O | 필드별 안전한 설명 |

규칙은 다음과 같다.

- 요청 DTO 필드 오류는 실제 필드 경로를 반환한다.
- 메서드 파라미터 오류는 파라미터 이름을 반환한다.
- 여러 필드 관계처럼 객체 단위 검증은 `field: "$"`를 사용한다.
- 중첩 필드는 경로를 유지한다.
- 비밀번호, passcode, token, secret, API Key, Authorization, credential, cookie, session 관련 값은 `rejectedValue: null`로 반환한다.
- 파일, 복합 객체, 컬렉션 전체는 반사하지 않는다.
- 안전한 단일 값도 최대 200자로 제한하며 초과분은 `…`로 생략한다.
- `message`에는 전체 요약을, `reason`에는 필드별 원인을 둔다.

민감 필드 예:

```json
{
  "field": "password",
  "constraint": "NotBlank",
  "rejectedValue": null,
  "reason": "비밀번호는 필수입니다."
}
```

## 5. HTTP 상태와 예외 매핑

`400`, `409`, `422`는 다음 기준으로 구분한다.

- `400 Bad Request`: JSON, 타입, 필수값, 필드 형식이 잘못되어 요청 자체가 유효하지 않다.
- `409 Conflict`: 요청은 유효하지만 현재 리소스 상태 또는 기존 데이터와 충돌한다.
- `422 Unprocessable Entity`: 요청 구조와 필드는 유효하지만 도메인 업무 규칙을 충족하지 않는다.

| 예외·상황 | HTTP | 오류 코드 |
|---|---:|---|
| `HttpMessageNotReadableException` | 400 | `COMMON_MALFORMED_JSON` |
| 필수 파라미터·헤더 누락, 타입 불일치 | 400 | `COMMON_INVALID_REQUEST` |
| `MethodArgumentNotValidException` | 400 | `COMMON_VALIDATION_FAILED` |
| `HandlerMethodValidationException` | 400 | `COMMON_VALIDATION_FAILED` |
| `ConstraintViolationException` | 400 | `COMMON_VALIDATION_FAILED` |
| 인증 실패 | 401 | `AUTH_UNAUTHENTICATED` |
| 보고서 기능 권한 부족 | 403 | `REPORT_ACCESS_DENIED` |
| 그 외 접근 권한 또는 CSRF 검증 실패 | 403 | `AUTH_FORBIDDEN` |
| 존재하지 않는 도메인 리소스 | 404 | `{DOMAIN}_NOT_FOUND` |
| 매핑되지 않은 경로·정적 리소스 | 404 | `COMMON_RESOURCE_NOT_FOUND` |
| `HttpRequestMethodNotSupportedException` | 405 | `COMMON_METHOD_NOT_ALLOWED` |
| 동일 멱등 요청이 아직 처리 중 | 409 | `COMMON_CONFLICT` |
| 같은 멱등 key의 다른 요청 재사용 | 409 | `IDEMPOTENCY_KEY_REUSED` |
| 중복 생성·상태 충돌 | 409 | `{DOMAIN}_CONFLICT` |
| 전역 요청 본문 상한 초과 | 413 | `COMMON_PAYLOAD_TOO_LARGE` |
| `MaxUploadSizeExceededException` | 413 | `IMAGE_TOO_LARGE` |
| `HttpMediaTypeNotSupportedException` | 415 | `COMMON_UNSUPPORTED_MEDIA_TYPE` |
| 이미지 콘텐츠 자체의 형식 검증 실패 | 415 | `IMAGE_UNSUPPORTED_MEDIA_TYPE` |
| 보고서 snapshot·상태 입력 업무 규칙 위반 | 422 | `REPORT_VALIDATION_FAILED` |
| 형식은 맞지만 그 외 업무 규칙 위반 | 422 | 구체적인 `{DOMAIN}_{REASON}` 또는 `COMMON_BUSINESS_RULE_VIOLATION` |
| Rate Limit 초과 | 429 | `COMMON_RATE_LIMIT_EXCEEDED` |
| 처리되지 않은 예외 | 500 | `COMMON_INTERNAL_ERROR` |
| LLM 응답 계약·원본 정합성 실패 | 502 | `LLM_RESPONSE_INVALID` |
| DB·S3·MQTT·LLM 장애 | 503 | `DB_UNAVAILABLE`, `S3_UNAVAILABLE`, `MQTT_UNAVAILABLE`, `LLM_UNAVAILABLE` |

예:

- 제목 누락 → `400 COMMON_VALIDATION_FAILED`
- 보고서 필수 통계 누락 → `422 REPORT_VALIDATION_FAILED`
- 동일 보고서 생성 요청이 처리 중 → `409 COMMON_CONFLICT`, 같은 key로 재시도
- 보고서 생성 key를 다른 body에 재사용 → `409 IDEMPOTENCY_KEY_REUSED`
- LLM 응답의 결함 ID가 Prompt allowlist 밖임 → `502 LLM_RESPONSE_INVALID`
- 존재하지 않는 로봇 → `404 ROBOT_NOT_FOUND`
- 실행 중인 임무를 다시 시작 → `409 COMMAND_STATE_CONFLICT`
- 보고서 시작일이 종료일보다 늦음 → `422 REPORT_VALIDATION_FAILED`
- 통계 `from`과 `to`가 같거나 두 Instant의 차이가 366일을 초과함 → `422 STATISTICS_PERIOD_INVALID`
- 통계 날짜·UUID를 파싱할 수 없음 → `400 COMMON_INVALID_REQUEST`
- 통계의 `patrolAreaId` UUID가 유효하지만 존재하지 않음 → `404 PATROL_AREA_NOT_FOUND`

## 6. 백엔드 구현

공통 구현은 다음 클래스에 있다.

| 클래스 | 역할 |
|---|---|
| `ErrorCode` | 오류 코드, HTTP 상태, 기본 안전 메시지 |
| `ErrorResponse` | 공통 오류 응답 |
| `ValidationErrorDetail` | 필드·파라미터 Validation 상세 |
| `BusinessException` | 공개 가능한 `ErrorCode`를 운반하는 공통 업무 예외 |
| `GlobalExceptionHandler` | 프레임워크·Validation·업무·미처리 예외 변환 |
| `RequestIdFilter` | 유효한 외부 UUID 수용 또는 신규 UUID 생성, 헤더·로그 MDC 연결 |
| `RequestBodySizeLimitFilter` | 선언된 길이와 실제 streaming body를 모두 계수해 전역 상한 적용 |

미처리 예외 로그는 다음 원칙을 따른다.

- 예외 message, cause, stack trace를 로깅 호출의 인자로 넘기지 않는다.
- SQL·bind 값·테이블명·제약조건명 대신 `category=DATABASE`만 기록한다.
- EC2 운영 profile은 `server.error.include-*`를 비활성화하고 전역 throwable
  변환을 `%nopex`로 설정한다.
- Hibernate SQL·bind·extract, JDBC core, Flyway SQL script logger는 운영에서
  `OFF`로 고정한다.
- 장애 상관관계는 응답 헤더·본문·로그에 공통으로 기록되는 `requestId`로 추적한다.

도메인 서비스는 다음처럼 구체적인 오류 코드를 사용한다.

```java
throw new BusinessException(ErrorCode.ROBOT_NOT_FOUND);
throw new BusinessException(ErrorCode.COMMAND_STATE_CONFLICT);
throw new BusinessException(ErrorCode.REPORT_VALIDATION_FAILED);
throw new BusinessException(ErrorCode.STATISTICS_PERIOD_INVALID);
```

`BusinessException`에 전달하는 사용자 정의 메시지는 API에 노출되므로 개인정보나 내부 구현 정보를 포함하면 안 된다.

Spring Security 도입 후에는 필터 단계의 인증·인가 실패도 같은 JSON을 반환하도록 `AuthenticationEntryPoint`와 `AccessDeniedHandler`를 각각 `AUTH_UNAUTHENTICATED`, `AUTH_FORBIDDEN`에 연결한다. Controller Advice만으로는 Security Filter Chain에서 발생한 오류를 처리할 수 없다.

## 7. OpenAPI 적용 규칙

- 성공 응답에는 실제 DTO schema를 직접 연결한다.
- 오류 응답에는 `#/components/schemas/ErrorResponse`를 연결한다.
- 반복되는 HTTP 오류는 `#/components/responses/*`를 참조한다.
- 런타임에 새 오류 코드를 구현하면 백엔드 `ErrorCode`, 이 문서, OpenAPI `ErrorCode.enum`, 관련 테스트를 같은 변경에서 갱신한다. Day 1에서 계약만 먼저 확정한 코드는 해당 기능 구현 작업에서 백엔드 enum과 테스트를 추가한다.
- `openapi-v1.yaml`을 lint하고 Swagger UI에서 오류 예시와 필수 필드를 확인한다.

## 8. 완료 조건과 테스트

다음 항목을 모두 만족해야 공통 API 응답·오류 계약 변경이 완료된 것으로 본다.

- 성공 DTO가 공통 envelope 없이 반환된다.
- 모든 오류에 `timestamp`, `status`, `code`, `message`, `path`, `requestId`, `details`가 존재한다.
- 응답 `X-Request-Id`, 응답 본문 `requestId`, 서버 로그 MDC가 동일하다.
- Bean Validation과 메서드 파라미터 Validation이 필드별 상세를 반환한다.
- 민감한 `rejectedValue`와 내부 예외 메시지가 노출되지 않는다.
- 운영 로그에 예외 message, stack trace, SQL, bind 값, Secret이 노출되지 않는다.
- `400`, `409`, `413`, `415`, `422`, `500`, `503` 매핑 테스트가 존재한다.
- 오류 코드 이름 규칙과 기본 메시지를 테스트한다.
- OpenAPI가 파싱되고 공통 schema·response 참조가 유효하다.
