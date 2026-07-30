# 보고서 상태 계약

- 문서 상태: 확정
- 대상 테이블: `report.anomaly_report`, `report.report_version`
- DB 정본:
  [`V2__create_report_and_audit_schema.sql`](../backend/src/main/resources/db/migration/V2__create_report_and_audit_schema.sql),
  [`V30__harden_report_domain_invariants.sql`](../backend/src/main/resources/db/migration/V30__harden_report_domain_invariants.sql)
- OpenAPI 정본: [`openapi-v1.yaml`](./openapi-v1.yaml)

## 1. 상태

| 상태 | 의미 | 수정 | 소프트 삭제 | 승인·반려 |
|---|---|:---:|:---:|:---:|
| `DRAFT` | 작성 중인 초안 | O | O | X |
| `IN_REVIEW` | 검토 요청되어 내용이 고정된 상태 | X | X | O |
| `APPROVED` | 승인 완료된 종료 상태 | X | X | X |
| `REJECTED` | 검토자가 반려한 상태 | O | O | X |

`APPROVED` 보고서는 감사와 재현성을 위해 수정·삭제할 수 없다. 승인 후 정정이 필요하면 기존 보고서를 변경하지 않고 별도 보고서를 생성한다.

DB trigger와 `CHECK`는 허용 전이 순서, 버전 증가, 승인·삭제 metadata 정합성을 검사한다. 애플리케이션의 중앙 상태 서비스는 같은 전이를 먼저 검사하고 권한·자기 승인·`expectedVersionNo` 같은 요청 문맥 규칙을 추가로 적용한다.

## 2. 상태 전이

```text
생성 ───────────────→ DRAFT
                       │ submit
                       ▼
                   IN_REVIEW
                    │      │
             approve│      │reject
                    ▼      ▼
               APPROVED  REJECTED
                            │ update
                            └────────→ DRAFT
```

| 현재 | 요청 | 다음 | 권한 | 조건 |
|---|---|---|---|---|
| 없음 | 보고서 생성 | `DRAFT` | `REPORT_CREATE` | 유효한 기간·제목과 첫 버전 |
| `DRAFT` | 본문 수정 | `DRAFT` | `REPORT_UPDATE` | 새 `report_version` 생성 |
| `DRAFT` | 검토 요청 | `IN_REVIEW` | `REPORT_UPDATE` | 최신 버전이며 내용이 비어 있지 않음 |
| `IN_REVIEW` | 승인 | `APPROVED` | `REPORT_APPROVE` | 승인자가 최신 버전 작성자와 다름 |
| `IN_REVIEW` | 반려 | `REJECTED` | `REPORT_APPROVE` | 반려 사유 필수 |
| `REJECTED` | 본문 수정 | `DRAFT` | `REPORT_UPDATE` | 새 `report_version` 생성 |
| `DRAFT` | 삭제 | 삭제 표시 | `REPORT_UPDATE` | `deleted_at` 기록, 모든 버전 보존 |
| `REJECTED` | 삭제 | 삭제 표시 | `REPORT_UPDATE` | `deleted_at` 기록, 반려 감사와 모든 버전 보존 |

표에 없는 전이는 허용하지 않고 `409 COMMAND_STATE_CONFLICT`가 아니라 보고서 전용 `409 REPORT_STATE_CONFLICT`를 사용한다. `ReportStateTransitionService`와 DB lifecycle trigger가 동일한 전이 표를 강제한다.

## 3. 동작 계약

### 3.1 생성

- 수동·템플릿·LLM·시스템 생성 모두 최초 상태는 `DRAFT`다.
- `reportType`은 `PERIODIC`, `ISSUE` 중 하나이고, `generationMethod`는 `MANUAL`, `TEMPLATE`, `LLM`, `SYSTEM` 중 하나다.
- `periodTo`는 `periodFrom`보다 빠를 수 없다.
- 생성 시 `anomaly_report.current_version_no=1`인 header와 `version_no=1`인 첫 `report_version`을 같은 트랜잭션에 만든다.
- 사용자 생성은 `creator_type=USER`, `created_by` 필수이며 시스템 생성은 `creator_type=SYSTEM`, `created_by=NULL`이다.
- 생성 당시 조회 조건은 `filter_criteria` JSON object로 저장하고 이후 변경하지 않는다.
- LLM이 만든 결과도 자동 승인하지 않는다.

### 3.2 수정과 버전

- `DRAFT`, `REJECTED`만 수정할 수 있다.
- 수정할 때 기존 버전을 덮어쓰지 않고 `currentVersionNo + 1`로 새 버전을 만든다.
- 버전은 제목, 요약, 기간, 본문, 통계 snapshot, 근거 결함·임무·순찰 구역, 해당 버전의 생성 방식, LLM 모델, Prompt 버전, 변경 사유와 작성자를 함께 보존한다.
- header의 제목·요약·기간·`current_version_no`는 새 버전과 같은 트랜잭션에서 최신 버전 값으로 갱신한다.
- `REJECTED` 수정 성공 시 상태를 `DRAFT`로 되돌린다.
- 요청의 `expectedVersionNo`가 서버 `currentVersionNo`와 다르면 `409 REPORT_STATE_CONFLICT`로 거부한다.
- 기존 `report_version`은 상태와 관계없이 `UPDATE`, `DELETE`하지 않는다.
- 검토 중·승인된 버전의 본문과 통계 snapshot을 변경하지 않는다.

### 3.3 검토 요청

- `DRAFT`에서만 가능하다.
- 최신 버전의 제목, 본문, 기간과 필수 통계가 존재해야 한다.
- 성공 후 상태는 `IN_REVIEW`이고 내용 수정·삭제를 잠근다.
- 동일 요청의 재전송은 현재 상태를 반환하거나 멱등하게 처리하며 새 버전을 만들지 않는다.

### 3.4 승인

- `IN_REVIEW`에서만 가능하다.
- `REPORT_APPROVE` 권한과 대상 보고서 접근 범위를 검사한다.
- 승인자는 최신 `report_version.created_by`와 달라야 한다.
- 성공 시 `report_status=APPROVED`, `approved_version_no=current_version_no`, `approved_by`, `approved_at`을 같은 트랜잭션에 저장한다.
- `APPROVED`가 아닌 상태에서는 `approved_version_no`, `approved_by`, `approved_at`이 모두 `NULL`이어야 한다.
- 승인 후 생성한 PDF는 `approved_pdf_version_no`, 내부 storage key, SHA-256, 생성 시각을 한 bundle로 저장한다.
- `approved_pdf_version_no`는 `approved_version_no`와 반드시 같아야 하고, bundle은 한 번 결합한 뒤 덮어쓰지 않는다.

### 3.5 반려

- `IN_REVIEW`에서만 가능하다.
- `REPORT_APPROVE` 권한이 필요하다.
- 공백이 아닌 1~500자의 사유가 필수다.
- 반려는 본문 버전을 만들지 않고 상태와 감사 로그만 변경한다.
- 재작성을 시작하는 첫 수정이 새 버전을 만들고 `DRAFT`로 전환한다.

### 3.6 삭제

- `DRAFT`, `REJECTED`만 삭제할 수 있다.
- `anomaly_report.deleted_at`에 삭제 시각을 기록하는 소프트 삭제로 구현한다.
- 일반 목록·검색·상세 조회는 `deleted_at IS NULL`인 보고서만 반환한다.
- `anomaly_report`와 연결된 모든 `report_version` 행은 물리 삭제하지 않는다.
- DB는 `deleted_at IS NOT NULL`인 행의 상태를 `DRAFT`, `REJECTED`로 제한한다.
- `IN_REVIEW`, `APPROVED` 삭제 요청은 `409 REPORT_STATE_CONFLICT`다.

## 4. DB 저장 모델과 정합성

### 4.1 현재값 header

`report.anomaly_report`는 목록, 검색, 상태 전이에서 사용하는 현재값을 가진다.

- `report_id`는 내부 UUID이고 `report_code`는 유일한 외부 표시 코드다.
- `title`, `summary`, `period_from`, `period_to`는 현재 버전의 조회용 복제 값이다.
- `current_version_no`는 현재 화면과 다음 상태 전이가 참조하는 최신 버전 번호다.
- `approved_version_no`는 승인된 정확한 버전 번호이며 `APPROVED`에서만 존재한다.
- `generation_method`는 보고서의 최초 생성 방법이고, 이후 사람이 수정해도 생성 이력으로 유지한다.
- `deleted_at`은 소프트 삭제 marker다.
- 승인 PDF 네 필드는 전부 `NULL`이거나 전부 값이 있어야 하며, 승인 상태와 승인 버전이 일치할 때만 저장할 수 있다.

애플리케이션은 header의 현재값과 `current_version_no`가 가리키는 `report_version`의 값이 어긋나지 않도록 같은 트랜잭션에서 갱신한다.

### 4.2 불변 version

`report.report_version`은 보고서를 재현하기 위한 append-only snapshot이다.

- `(report_id, version_no)`는 유일하고 `version_no`는 1부터 증가한다.
- 제목, 요약, 기간, 본문, 통계 JSON object를 버전별로 저장한다.
- `generation_method`는 해당 버전의 작성 방식을 저장한다.
- `evidence_snapshot`은 `defectIds`, `missionIds`, `patrolAreaIds` 배열을 가진 JSON object다. 과거 근거 재현을 위해 현재 원본 테이블 FK가 아닌 ID snapshot으로 보존한다.
- LLM이 생성한 버전은 `llm_model`, `prompt_version`을 저장하되 Prompt 원문과 credential은 저장하지 않는다.
- 사용자 작성 버전은 `created_by`가 필수이고 시스템 작성 버전은 `created_by=NULL`이다.
- DB 권한과 trigger가 기존 버전의 `UPDATE`, `DELETE`를 모두 거부한다.

### 4.3 버전 포인터와 승인 정보

- `(report_id, current_version_no)`는 동일 보고서의 실제 `report_version`을 참조한다.
- `(report_id, approved_version_no)`도 동일 보고서의 실제 `report_version`을 참조한다.
- `(report_id, approved_pdf_version_no)`도 동일 보고서의 실제 승인 버전을 참조한다.
- 세 외래 키는 header와 version을 한 트랜잭션에 생성·변경할 수 있도록 commit 시점에 검사한다.
- `APPROVED`이면 승인자, 승인 시각, 승인 버전이 모두 존재해야 하고, 그 외 상태에서는 세 값이 모두 없어야 한다.
- DB trigger는 상태 전이 순서를 검사한다. 승인자가 최신 버전 작성자와 다른지, 요청의
  `expectedVersionNo`가 일치하는지 같은 요청 문맥 규칙은 서비스가 검사한다.

## 5. REST API 계약

모든 API는 인증 완료된 서버 세션을 사용한다. 조회를 제외한 상태 변경 요청은 `X-CSRF-TOKEN`이 필수이며, 소프트 삭제된 보고서는 일반 조회에서 `404 REPORT_NOT_FOUND`로 처리한다.

| Method | Path | 동작 | 권한 | 성공 |
|---|---|---|---|---|
| `GET` | `/api/v1/reports` | 목록·검색 | `REPORT_VIEW` | `200 ReportListResponse` |
| `POST` | `/api/v1/reports` | 수동·템플릿 보고서 생성(LLM 생성은 후속 단계) | `REPORT_CREATE` | `201 ReportDetailResponse` |
| `GET` | `/api/v1/reports/{reportId}` | 최신 버전 상세 | `REPORT_VIEW` | `200 ReportDetailResponse` |
| `PATCH` | `/api/v1/reports/{reportId}` | 새 버전 생성 | `REPORT_UPDATE` | `200 ReportDetailResponse` |
| `DELETE` | `/api/v1/reports/{reportId}` | 소프트 삭제 | `REPORT_UPDATE` | `204` |
| `GET` | `/api/v1/reports/{reportId}/versions` | 버전 목록 | `REPORT_VIEW` | `200 ReportVersionListResponse` |
| `GET` | `/api/v1/reports/{reportId}/versions/{versionNo}` | 특정 버전 상세 | `REPORT_VIEW` | `200 ReportVersionResponse` |
| `POST` | `/api/v1/reports/{reportId}/submit` | 검토 요청 | `REPORT_UPDATE` | `200 ReportDetailResponse` |
| `POST` | `/api/v1/reports/{reportId}/approve` | 승인 | `REPORT_APPROVE` | `200 ReportDetailResponse` |
| `POST` | `/api/v1/reports/{reportId}/reject` | 반려 | `REPORT_APPROVE` | `200 ReportDetailResponse` |

### 5.1 목록·검색

| Query | 타입 | 기본값 | 규칙 |
|---|---|---|---|
| `page` | integer | `0` | 0 이상 |
| `size` | integer | `20` | 1~100 |
| `search` | string | 없음 | trim 후 1~100자, `reportCode`·`title` 대소문자 무시 부분 검색 |
| `statuses` | comma-separated `ReportStatus` | 전체 | 중복 제거, 알 수 없는 값 거부 |
| `createdBy` | UUID | 전체 | 보고서 최초 작성자가 정확히 일치 |
| `periodFrom` | date-time | 없음 | 요청 시작 시각 이후까지 이어지는 보고서와 기간 겹침 검색 |
| `periodTo` | date-time | 없음 | 요청 종료 시각 이전부터 시작한 보고서와 기간 겹침 검색 |
| `reportType` | `PERIODIC` 또는 `ISSUE` | 전체 | 단일 값 |
| `generationMethod` | `MANUAL`, `TEMPLATE`, `LLM`, `SYSTEM` | 전체 | 단일 값 |
| `sort` | string | `createdAt,desc` | `createdAt`, `updatedAt`, `title`과 `asc`, `desc`만 허용 |

응답은 `content`, `page`, `size`, `totalElements`, `totalPages`를 갖는다. `content`에는 본문을 제외한 `ReportListItemResponse`를 반환한다.

### 5.2 생성과 `Idempotency-Key`

`POST /api/v1/reports`는 UUID 형식의 `Idempotency-Key` 요청 헤더를 필수로 받는다.

- key의 유일성 범위는 인증 사용자 전체이며, 최초 사용 시
  `POST + /api/v1/reports`에 결합된다. 같은 사용자가 다른 endpoint에 같은 key를
  재사용해도 `IDEMPOTENCY_KEY_REUSED`다.
- body는 JSON object key를 재귀적으로 정렬하고 배열 순서는 보존한 뒤 SHA-256을 계산한다.
- 같은 key와 같은 정규화 body는 최초 `201` 상태와 응답을 그대로 재사용한다.
- 같은 요청이 처리 중이면 별도 보고서를 생성하지 않고 안전한 `409 COMMON_CONFLICT`로
  동일 key 재시도를 안내한다. 먼저 시작한 트랜잭션이 완료되면 다음 재시도는 저장된
  성공 응답을 반환한다.
- 같은 key를 다른 body에 사용하면 `409 IDEMPOTENCY_KEY_REUSED`다.
- key와 요청 fingerprint, 안전한 응답은 생성 트랜잭션과 일관되게 저장하고 24시간 보존한다.
- 생성 트랜잭션이 실패하면 `PROCESSING` claim도 함께 rollback한다. 사용자가 입력을
  바꾸지 않은 재시도는 기존 key를 사용하고, 입력을 변경한 새 생성 요청은 새 key를 발급한다.

기본 조회·수정 API 단계에서 생성 body는 `generationMethod`로 구분하며
`MANUAL`, `TEMPLATE`만 활성화한다. `LLM` 요청 DTO와 결과 Schema는 다음 LLM 생성
단계의 고정 계약으로 유지하되, 그 구현이 연결되기 전에는 이 operation의 입력으로
광고하지 않는다.

| 생성 방법 | 필수 필드 | 규칙 |
|---|---|---|
| `MANUAL`, `TEMPLATE` | `generationMethod`, `reportType`, `title`, `periodFrom`, `periodTo`, `content`, `statistics` | `summary`, `filterCriteria` 선택 |
| `LLM` (후속 단계) | `generationMethod=LLM`, `reportType`, `periodFrom`, `periodTo` | `filterCriteria` 선택, 제목·본문·통계는 검증된 LLM 결과로 생성 |

브라우저 생성 API는 `SYSTEM`을 받지 않는다. `SYSTEM`은 서버 내부 일정 작업만 사용한다. 성공 응답은 항상 `DRAFT`, `currentVersionNo=1`인 `ReportDetailResponse`다.

### 5.3 상세와 버전

- `ReportDetailResponse`는 header 정보와 `currentVersion` 전체 snapshot을 함께 반환한다.
- `ReportVersionListResponse.content`는 본문을 제외한 버전 metadata를 `versionNo desc`로 반환한다.
- 특정 버전의 본문·통계가 필요하면 `/versions/{versionNo}`를 호출한다.
- 존재하지 않는 보고서, 삭제된 보고서, 보고서에 속하지 않는 버전은 `404 REPORT_NOT_FOUND`다.
- 상세 응답의 `approvedVersionNo`는 `APPROVED`에서만 값이 있고 그 외에는 `null`이다.
- 저장된 검색 조건이 없는 보고서는 `filterCriteria`를 `null`이 아닌 빈 객체(`{}`)로 반환한다.
- 목록과 상세는 최초 작성자와 최신 버전 작성자의 UUID·표시명을 각각
  `createdBy`/`createdByDisplayName`, `updatedBy`/`updatedByDisplayName`으로 반환한다.
- 상세는 마지막 성공 `REPORT_REJECTED` 감사의 사유를 `lastRejectionReason`으로
  반환한다. 반려본을 수정해 `DRAFT`가 되어도 이 이력은 보존된다.
- 승인 PDF가 결합됐으면 `approvedPdf`에 승인 `versionNo`, SHA-256 hex,
  `createdAt`만 반환한다. 내부 object storage key는 API에 노출하지 않는다.

### 5.4 수정과 상태 변경 요청

| 동작 | 입력 | 추가 규칙 |
|---|---|---|
| 수정 | `ReportUpdateRequest` | `expectedVersionNo`와 변경할 필드 1개 이상 필수 |
| 삭제 | query `expectedVersionNo` | `DRAFT`, `REJECTED`만 허용 |
| submit | `{ "expectedVersionNo": integer }` | `DRAFT`만 허용 |
| approve | `{ "expectedVersionNo": integer, "reason"?: string }` | 최신 버전 작성자는 승인 불가 |
| reject | `{ "expectedVersionNo": integer, "reason": string }` | 공백이 아닌 1~500자 사유 필수 |

수정 가능한 snapshot 필드는 `title`, `summary`, `periodFrom`, `periodTo`, `content`, `statistics`이며 생략한 값은 직전 버전에서 복사한다. `summary: null`은 요약을 명시적으로 비운다. `changeNote`는 500자 이하의 안전한 변경 사유다.

## 6. 오류 코드

| 코드 | HTTP | 적용 조건 | 클라이언트 처리 |
|---|---:|---|---|
| `REPORT_STATE_CONFLICT` | 409 | 허용되지 않은 상태 전이, `expectedVersionNo` 불일치, 자기 승인 | 상세 재조회 후 UI 갱신 |
| `REPORT_ACCESS_DENIED` | 403 | 인증 사용자가 필요한 REPORT 권한을 갖지 않음 | 버튼·화면 차단, 권한 갱신 전 재시도 금지 |
| `REPORT_VALIDATION_FAILED` | 422 | 기간·본문·통계·반려 사유가 보고서 업무 규칙을 충족하지 않음 | 입력 보정 |
| `IDEMPOTENCY_KEY_REUSED` | 409 | 같은 멱등 key를 다른 요청 fingerprint에 재사용 | 새 UUID key로 사용자의 새 요청만 재시도 |
| `LLM_UNAVAILABLE` | 503 | LLM 비활성, timeout, 연결·provider 장애 | `Retry-After` 이후 재시도 또는 수동 작성 |
| `LLM_RESPONSE_INVALID` | 502 | provider 응답이 JSON Schema 또는 원본 데이터 검증에 실패 | 자동 재사용 금지, 수동 작성 안내 |

인증되지 않은 세션은 `401 AUTH_UNAUTHENTICATED`, CSRF 실패는 `403 AUTH_FORBIDDEN`, 없는 보고서는 `404 REPORT_NOT_FOUND`를 사용한다. 필수 헤더 누락·JSON 파싱·타입 오류는 공통 `400` 계열 오류를 사용한다.

## 7. LLM 결과 JSON Schema

LLM 정규화 결과의 정본은 [`report-llm-result.schema.json`](./report-llm-result.schema.json)이다. 필수 최상위 필드는 다음과 같다.

| 필드 | 저장·검증 |
|---|---|
| `title` | `report_version.title`, 1~200자 |
| `summary` | `report_version.summary`, 1~255자 |
| `statistics` | `report_version.statistics`, 기존 통계 API와 같은 aggregate·상태별·유형별 snapshot |
| `keyDefects` | 검증 후 보고서 `content`의 주요 결함 절로 렌더링 |
| `actionRecommendations` | 검증 후 보고서 `content`의 조치 권고 절로 렌더링 |
| `period` | 요청의 `periodFrom`, `periodTo`와 정확히 일치해야 함 |
| `promptVersion` | `report_version.prompt_version`, 실행 설정값과 일치해야 함 |
| `modelIdentifier` | `report_version.llm_model`, 실제 호출 모델과 일치해야 함 |

provider의 raw text를 직접 저장하지 않는다. LLM adapter가 provider 응답을 JSON으로 파싱하고 실행 설정의 Prompt 버전·모델 식별자를 결합한 결과를 Schema로 검증한다. 이어서 다음 원본 정합성을 검사한다.

- `statistics`는 같은 기간·필터로 서버가 계산한 snapshot과 일치한다.
- `keyDefects[].defectId`, `relatedDefectIds`는 Prompt 입력 allowlist에 있던 결함만 참조한다.
- 기간, 결함 코드·유형·위험도는 DB 원본과 일치한다.
- 알 수 없는 필드, 허용 개수 초과, HTML·명령 실행을 전제로 한 출력은 거부한다.

한 단계라도 실패하면 보고서와 첫 버전을 저장하지 않고 `502 LLM_RESPONSE_INVALID`를 반환한다. 유효한 결과도 항상 `DRAFT`로만 저장한다.

## 8. 권한과 UI 버튼 매트릭스

### 8.1 역할별 기본 권한

| 권한 | 작업 | ADMIN | MANAGER | OPERATOR |
|---|---|:---:|:---:|:---:|
| `REPORT_VIEW` | 목록·검색·상세·버전 조회 | O | O | O |
| `REPORT_CREATE` | 수동·템플릿·LLM 생성 | O | O | O |
| `REPORT_UPDATE` | 초안·반려본 수정, 삭제, 검토 요청 | O | O | O |
| `REPORT_APPROVE` | 승인·반려 | O | O | X |

사용자 정의 역할은 위 권한을 독립적으로 조합할 수 있다. 보고서 메뉴와 route 진입은 `REPORT_VIEW`가 필요하다.

### 8.2 상태·권한별 버튼

| 화면·상태 | 버튼 | 표시·활성 조건 | 성공 후 |
|---|---|---|---|
| 목록 | 새 보고서 | `REPORT_CREATE` | 생성된 `DRAFT` 상세 |
| `DRAFT` | 수정 | `REPORT_UPDATE` | 새 버전, `DRAFT` |
| `DRAFT` | 검토 요청 | `REPORT_UPDATE` | `IN_REVIEW` |
| `DRAFT` | 삭제 | `REPORT_UPDATE` | 목록으로 이동 |
| `IN_REVIEW` | 승인 | `REPORT_APPROVE`이고 현재 사용자 ≠ 최신 버전 작성자 | `APPROVED` |
| `IN_REVIEW` | 반려 | `REPORT_APPROVE`; 자기 작성 버전도 반려 가능 | `REJECTED` |
| `REJECTED` | 수정 | `REPORT_UPDATE` | 새 버전, `DRAFT` |
| `REJECTED` | 삭제 | `REPORT_UPDATE` | 목록으로 이동 |
| `APPROVED` | 수정·검토·승인·반려·삭제 | 항상 숨김 | 해당 없음 |
| 모든 비삭제 상태 | 버전 보기 | `REPORT_VIEW` | 상태 변경 없음 |

프론트 버튼 숨김은 사용성 규칙이며 보안 통제가 아니다. 서버는 모든 요청에서 권한, 상태, `expectedVersionNo`, 자기 승인 금지를 다시 검사한다.

## 9. 동시성·멱등성

- 생성은 `Idempotency-Key`를 요구한다.
- 생성 멱등 행은 요청 주체·key·canonical body의 SHA-256만 저장하고 원문 key와 body는 저장하지 않는다.
- canonical body는 DTO로 타입 정규화한 뒤 object key를 재귀 정렬한다. 배열 순서는 의미가
  있을 수 있으므로 변경하지 않는다.
- 성공 시 `resource_type=REPORT`, 생성된 `resource_id`, 최초 201 응답 snapshot을 저장하며 `created_at + 24시간`에 만료한다. 이후 보고서가 수정돼도 replay 응답은 최초 생성 응답과 같다.
- `(expires_at)` 보고서 전용 partial index를 사용해 기본 10분마다 최대 1,000건을
  물리 삭제한다. 잠긴 행은 `SKIP LOCKED`로 건너뛴다.
- claim·보고서·첫 version·감사·응답 snapshot은 한 트랜잭션이다. 실패 시 모두 rollback되어
  동일 작업은 기존 key로 재시도할 수 있다.
- committed `PROCESSING` claim은 중복 실행하지 않고 `409 COMMON_CONFLICT`를 반환한다.
  동일 key·다른 fingerprint는 처리 상태와 관계없이 `409 IDEMPOTENCY_KEY_REUSED`다.
- 수정·삭제·submit·approve·reject는 `expectedVersionNo`를 사용하고 서버의 `current_version_no`와 비교한다.
- 수정은 새 version INSERT와 header 갱신을 한 트랜잭션에서 처리한다.
- 변경 서비스는 join snapshot이 아니라 활성 header만 먼저 `SELECT ... FOR UPDATE`로 잠근다.
  잠금 대기 중 version pointer가 바뀔 수 있으므로 잠금 획득 후 최신 header/version을 다시
  읽고 상태와 `current_version_no`를 검사한 다음 version·header·감사를 기록한다.
- 동일 버전에 대해 최초 성공한 상태 전이만 반영한다.
- 경합에서 패한 요청은 보고서 본문·통계·현재 version 값을 포함하지 않는
  `409 REPORT_STATE_CONFLICT`를 반환한다. 클라이언트는
  `GET /api/v1/reports/{reportId}`를 다시 호출한 뒤 화면을 갱신한다.

## 10. 감사와 이벤트

| 동작 | 감사 action | SSE |
|---|---|---|
| 생성 | `REPORT_CREATED` | `report.updated` |
| 수정·새 버전 | `REPORT_UPDATED` | `report.updated` |
| 검토 요청 | `REPORT_SUBMITTED` | `report.updated` |
| 승인 | `REPORT_APPROVED` | `report.updated` |
| 반려 | `REPORT_REJECTED` | `report.updated` |
| 삭제 | `REPORT_DELETED` | `report.updated` |

감사 로그에는 report ID, 이전·이후 상태, 버전 번호, 결과와 안전한 사유만 저장한다. 보고서 본문 전체와 통계 snapshot을 감사 로그에 복제하지 않는다.

## 11. 구현 인수 조건

- OpenAPI의 보고서 operation·DTO로 프론트 Mock 응답을 만들 수 있음
- 허용 전이 전체와 표에 없는 전이 거부 테스트
- 승인된 보고서 수정·삭제 거부 테스트
- 반려 후 수정 시 새 버전과 `DRAFT` 전환 테스트
- header 현재값과 최신 version snapshot의 트랜잭션 정합성 테스트
- 기존 `report_version` 수정·삭제 거부 테스트
- `DRAFT`, `REJECTED` 소프트 삭제와 조회 제외 테스트
- 최신 버전 작성자의 자기 승인 거부 테스트
- `expectedVersionNo` 경합 테스트
- 동일 key 동시 생성 시 한 건 생성·한 건 replay 테스트
- 동일 version 동시 수정과 submit·approve/reject 경합 테스트
- 처리 중 요청의 중복 생성 차단과 실패 후 동일 key 재시도 테스트
- `Idempotency-Key` 동일 요청 replay와 다른 body 재사용 거부 테스트
- LLM JSON Schema와 원본 allowlist 검증 테스트
- 여섯 전용 오류 코드의 HTTP 상태·응답 테스트
- 상태 변경, 승인자·시각, 감사 로그의 트랜잭션 정합성 테스트
- 상태별 역할·권한·UI 버튼 매트릭스 테스트
