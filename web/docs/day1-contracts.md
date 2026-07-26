# Day 1 보안·업무 계약

- 문서 상태: 확정
- 적용 범위: 관리자 웹 REST/SSE API
- 작성 목적: Day 3·4에서 추가 정책 결정 없이 구현과 테스트를 시작할 수 있는 계약 확정
- 비적용 범위: Spring Security, 세션, TOTP, RBAC, 감사 로그의 런타임 구현과 DB 마이그레이션

## 1. 정본 문서

| 계약 | 정본 |
|---|---|
| 공통 성공·오류·Validation | [`common-api-response-and-errors.md`](./common-api-response-and-errors.md) |
| REST/SSE 공통 API·정본·버전 계약 | [`api-contract.md`](./api-contract.md) |
| 관리자 로그인·세션·쿠키·CSRF·TOTP | [`admin-authentication-security.md`](./admin-authentication-security.md) |
| 역할·권한·API 접근 매트릭스 | [`authorization-matrix.md`](./authorization-matrix.md) |
| 감사 로그·민감정보 처리 | [`audit-and-sensitive-data-policy.md`](./audit-and-sensitive-data-policy.md) |
| 보호 자산·접근 지점·관리자·AWS·LLM 위협 | [`threat-model.md`](./threat-model.md) |
| local/test/docker/staging/prod 설정·Secret 전략 | [`environment-configuration-strategy.md`](./environment-configuration-strategy.md) |
| 보고서 상태와 전이 | [`report-status-contract.md`](./report-status-contract.md) |
| 기계 판독 API 계약 | [`openapi-v1.yaml`](./openapi-v1.yaml) |

문서와 OpenAPI가 충돌하면 OpenAPI의 요청·응답 형식은 OpenAPI를, 보안 및 업무 정책은 위 주제별 Markdown 문서를 우선한다. 충돌을 발견한 변경은 같은 작업에서 두 정본을 함께 수정한다.

### 기존 PDF API 명세에서 확정 변경된 항목

이 문서 세트는 기존 `길봄_MQTT 통신_SWAGGER REST SSE_API 명세서.pdf`의 다음 인증 항목을 대체한다.

| 기존 초안 | Day 1 확정 계약 |
|---|---|
| 로그인 성공 즉시 `200` 사용자 응답 | 비밀번호 성공은 `202` MFA 대기, TOTP·복구 성공 후에만 인증 완료 |
| 로그인 요청에 CSRF 없음 | 로그인 전 공개 CSRF 발급 후 로그인에도 `X-CSRF-TOKEN` 필수 |
| CSRF 발급은 로그인 사용자만 가능 | `/api/v1/auth/csrf-token`은 익명 세션에도 허용 |
| `GILBOM_SESSION` | 운영 `__Host-GILBOM_SESSION`, 로컬 HTTP만 `GILBOM_SESSION` |
| 잠금·비활성 계정을 `423`으로 구분 | 계정 열거 방지를 위해 외부에는 공통 `401 AUTH_UNAUTHENTICATED` |
| MFA API 없음 | TOTP 등록·확인·검증·복구·초기화 API 확정 |

## 2. 확정 결정

| ID | 결정 |
|---|---|
| `AUTH-001` | 관리자 인증은 JWT가 아닌 서버 세션을 사용한다. 운영 세션 저장소는 PostgreSQL 기반 Spring Session JDBC로 구현한다. |
| `AUTH-002` | 운영 인증 쿠키는 `__Host-GILBOM_SESSION`이며 `Secure`, `HttpOnly`, `SameSite=Lax`, `Path=/`, Domain 미설정을 적용한다. |
| `AUTH-003` | CSRF 토큰은 세션에 저장하고 `X-CSRF-TOKEN` 헤더로 전달한다. 로그인·로그아웃을 포함한 모든 상태 변경 요청에 적용한다. |
| `AUTH-004` | 모든 관리자 역할은 비밀번호와 TOTP MFA를 모두 통과해야 인증 완료된다. 비밀번호 성공만으로 업무 권한을 부여하지 않는다. |
| `AUTH-005` | 로그인 실패는 계정 존재·비밀번호·MFA 중 어느 단계가 실패했는지 외부에 상세히 노출하지 않는다. |
| `AUTH-006` | 비밀번호, API Key, MQTT 비밀번호는 원문 저장하지 않는다. 세션 ID는 지정 세션 저장소 외에 저장하지 않으며 어떤 로그에도 기록하지 않는다. |
| `AUTHZ-001` | 역할은 권한 묶음이고 서버 API는 역할명이 아니라 세부 권한 코드로 인가한다. 미등록 API는 기본 거부한다. |
| `AUTHZ-002` | 활성 역할 권한의 합집합에 사용자 예외를 적용하고, 동일 권한의 `DENY`가 `ALLOW`보다 우선한다. |
| `AUTHZ-003` | 로봇과 연결된 리소스는 권한 코드와 `OWNER/OPERATOR/VIEWER` 객체 범위를 모두 검사한다. |
| `API-001` | `/api/v1`, DTO 직접 성공 응답, 공통 오류, 0-based 페이지, UTC, UUID, 멱등성, Private S3와 SSE 규칙을 v1 공통 계약으로 사용한다. |
| `API-002` | 구현하는 endpoint는 같은 변경에서 OpenAPI operation·schema와 권한 매트릭스를 갱신해야 하며 구현 후 PDF만을 런타임 계약으로 사용할 수 없다. |
| `AUDIT-001` | 로그인·권한·명령·결함·보고서·설정 변경은 추가 전용 감사 로그로 남긴다. 민감값은 전후 값과 사유에서도 제거한다. |
| `THREAT-001` | 인터넷·브라우저·로봇 LTE·업로드·LLM 출력은 신뢰하지 않고 Nginx, MQTT, S3, LLM, CI/CD·AWS 관리면을 별도 신뢰 경계로 검사한다. |
| `THREAT-002` | 운영 AWS 정적 Access Key와 `.env` Secret을 금지하고 workload IAM role, Secrets Manager, KMS를 사용한다. LLM에는 allowlist 데이터만 보내고 출력은 항상 미신뢰 `DRAFT`로 처리한다. |
| `CONFIG-001` | 환경은 `local`, `test`, `docker`, `staging`, `prod` 중 하나로 구분하고 동일 image에 외부 설정만 주입한다. `docker`는 운영 환경이 아니다. |
| `CONFIG-002` | staging/prod Secret은 `.env`가 아니라 Secrets Manager·KMS에서 주입하고 안전하지 않은 cookie·CORS·session·integration 설정은 시작 시 실패시킨다. |
| `REPORT-001` | 보고서 상태는 `DRAFT`, `IN_REVIEW`, `APPROVED`, `REJECTED`로 고정한다. `APPROVED`는 변경·삭제할 수 없는 종료 상태다. |
| `REPORT-002` | 현재 버전 작성자는 자신의 버전을 승인할 수 없다. 승인과 반려에는 `REPORT_APPROVE` 권한이 필요하다. |

## 3. 일정별 구현 경계

### Day 3 — 로그인·세션·로그인 공격 방어

- Spring Security와 Spring Session JDBC 구성
- 비밀번호 검증과 로그인 시도 제한
- 세션 생성·교체·만료·동시 세션 제한
- 보안 쿠키 적용
- TOTP 등록·검증·복구
- 인증 필터 단계 공통 오류 응답
- 인증·세션·MFA 단위 및 통합 테스트

### Day 4 — RBAC·CSRF·관리자·감사 로그

- 세션 기반 CSRF 토큰과 헤더 검증
- 역할·권한 초기 데이터 및 유효 권한 계산
- URL·메서드·객체 단위 인가
- 관리자 역할·사용자·로봇 배정 API
- 감사 로그 기록과 조회
- 권한 매트릭스·IDOR·CSRF 통합 테스트

보고서 상태 전이는 보고서 기능 구현 시 [`report-status-contract.md`](./report-status-contract.md)를 그대로 적용한다.

## 4. Day 1 완료 조건

- [x] 서버 세션, 쿠키, CSRF, TOTP 흐름과 만료 정책이 확정됨
- [x] 로그인 공격 방어와 인증 오류 노출 기준이 확정됨
- [x] 역할별 권한과 전체 API 접근 조건이 표로 작성됨
- [x] 객체 단위 권한 계산과 기본 거부 원칙이 확정됨
- [x] 감사 이벤트, 필드, 저장·실패·보존 기준이 확정됨
- [x] 비밀번호, API Key, 세션 ID 및 관련 민감값의 저장·로그 금지 기준이 확정됨
- [x] 보호할 데이터, 외부 접근 지점, 관리자 권한, AWS·LLM Secret 위협과 필수 통제가 확정됨
- [x] API 정본 우선순위, 공통 HTTP·페이지·멱등성·파일·SSE·버전 규칙이 확정됨
- [x] local/test/docker/staging/prod 설정, Secret 출처, fail-fast와 배포·회전 전략이 확정됨
- [x] 보고서 상태, 전이, 권한, 불변 조건이 확정됨
- [x] 인증·MFA·CSRF·보고서 상태 요청과 응답이 OpenAPI에 반영됨
