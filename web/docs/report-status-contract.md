# 보고서 상태 계약

- 문서 상태: 확정
- 대상 테이블: `report.anomaly_report`, `report.report_version`
- OpenAPI 정본: [`openapi-v1.yaml`](./openapi-v1.yaml)

## 1. 상태

| 상태 | 의미 | 수정 | 삭제 | 승인·반려 |
|---|---|:---:|:---:|:---:|
| `DRAFT` | 작성 중인 초안 | O | O | X |
| `IN_REVIEW` | 검토 요청되어 내용이 고정된 상태 | X | X | O |
| `APPROVED` | 승인 완료된 종료 상태 | X | X | X |
| `REJECTED` | 검토자가 반려한 상태 | O | O | X |

`APPROVED` 보고서는 감사와 재현성을 위해 수정·삭제할 수 없다. 승인 후 정정이 필요하면 기존 보고서를 변경하지 않고 별도 보고서를 생성한다.

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
| 없음 | 보고서 생성 | `DRAFT` | `REPORT_CREATE` | 유효한 기간·근거·제목 |
| `DRAFT` | 본문 수정 | `DRAFT` | `REPORT_UPDATE` | 새 `report_version` 생성 |
| `DRAFT` | 검토 요청 | `IN_REVIEW` | `REPORT_UPDATE` | 최신 버전이며 내용이 비어 있지 않음 |
| `IN_REVIEW` | 승인 | `APPROVED` | `REPORT_APPROVE` | 승인자가 최신 버전 작성자와 다름 |
| `IN_REVIEW` | 반려 | `REJECTED` | `REPORT_APPROVE` | 반려 사유 필수 |
| `REJECTED` | 본문 수정 | `DRAFT` | `REPORT_UPDATE` | 새 `report_version` 생성 |
| `DRAFT` | 삭제 | 삭제 표시 | `REPORT_UPDATE` | 승인·검토 이력 없음 |
| `REJECTED` | 삭제 | 삭제 표시 | `REPORT_UPDATE` | 반려 사유와 버전은 감사·이력으로 보존 |

표에 없는 전이는 허용하지 않고 `409 COMMAND_STATE_CONFLICT`가 아니라 보고서 전용 `409 REPORT_STATE_CONFLICT`를 사용한다. OpenAPI와 공통 오류 문서에는 계약을 먼저 등록했으며 백엔드 enum과 테스트는 보고서 기능 구현 시 추가한다.

## 3. 동작 계약

### 3.1 생성

- 수동·템플릿·LLM·시스템 생성 모두 최초 상태는 `DRAFT`다.
- `periodTo`는 `periodFrom`보다 빠를 수 없다.
- 생성 시 `currentVersionNo=1`과 첫 `report_version`을 같은 트랜잭션에 만든다.
- LLM이 만든 결과도 자동 승인하지 않는다.

### 3.2 수정과 버전

- `DRAFT`, `REJECTED`만 수정할 수 있다.
- 수정할 때 기존 버전을 덮어쓰지 않고 `currentVersionNo + 1`로 새 버전을 만든다.
- `REJECTED` 수정 성공 시 상태를 `DRAFT`로 되돌린다.
- 요청의 `expectedVersionNo`가 서버 `currentVersionNo`와 다르면 `409 REPORT_STATE_CONFLICT`로 거부한다.
- 검토 중·승인된 버전의 본문, 통계 snapshot, 근거 결함·임무를 변경하지 않는다.

### 3.3 검토 요청

- `DRAFT`에서만 가능하다.
- 최신 버전의 제목, 본문, 기간과 필수 통계가 존재해야 한다.
- 성공 후 상태는 `IN_REVIEW`이고 내용 수정·삭제를 잠근다.
- 동일 요청의 재전송은 현재 상태를 반환하거나 멱등하게 처리하며 새 버전을 만들지 않는다.

### 3.4 승인

- `IN_REVIEW`에서만 가능하다.
- `REPORT_APPROVE` 권한과 대상 보고서 접근 범위를 검사한다.
- 승인자는 최신 `report_version.created_by`와 달라야 한다.
- 성공 시 `report_status=APPROVED`, `approved_by`, `approved_at`을 같은 트랜잭션에 저장한다.
- 승인 후 생성한 PDF는 승인 버전 번호와 결합하며 이후 덮어쓰지 않는다.

### 3.5 반려

- `IN_REVIEW`에서만 가능하다.
- `REPORT_APPROVE` 권한이 필요하다.
- 공백이 아닌 1~500자의 사유가 필수다.
- 반려는 본문 버전을 만들지 않고 상태와 감사 로그만 변경한다.
- 재작성을 시작하는 첫 수정이 새 버전을 만들고 `DRAFT`로 전환한다.

### 3.6 삭제

- `DRAFT`, `REJECTED`만 삭제할 수 있다.
- 실제 행과 버전 이력은 즉시 물리 삭제하지 않고 목록에서 제외되는 소프트 삭제로 구현한다.
- 현재 DDL에 보고서 `deleted_at`이 없으므로 구현 전 DB 마이그레이션에 추가해야 한다.
- `IN_REVIEW`, `APPROVED` 삭제 요청은 `409 REPORT_STATE_CONFLICT`다.

## 4. 권한과 분리

| 작업 | 권한 |
|---|---|
| 목록·상세·버전·다운로드 | `REPORT_VIEW` |
| 생성 | `REPORT_CREATE` |
| 초안·반려본 수정, 삭제, 검토 요청 | `REPORT_UPDATE` |
| 승인, 반려, 자동 생성 일정 관리 | `REPORT_APPROVE` |

`ADMIN`과 `MANAGER`는 승인 권한을 가지지만 자신의 최신 버전은 승인할 수 없다. `OPERATOR`는 생성·수정·검토 요청까지 수행하고 승인·반려할 수 없다.

## 5. 동시성·멱등성

- 생성은 `Idempotency-Key`를 요구한다.
- 수정·submit·approve·reject는 `expectedVersionNo`를 사용한다.
- 동일 버전에 대해 최초 성공한 상태 전이만 반영한다.
- 경합에서 패한 요청은 현재 상태와 버전 번호를 포함하지 않는 안전한 `409 REPORT_STATE_CONFLICT` 오류를 반환하고 클라이언트가 상세를 재조회하게 한다.

## 6. 감사와 이벤트

| 동작 | 감사 action | SSE |
|---|---|---|
| 생성 | `REPORT_CREATED` | `report.updated` |
| 수정·새 버전 | `REPORT_UPDATED` | `report.updated` |
| 검토 요청 | `REPORT_SUBMITTED` | `report.updated` |
| 승인 | `REPORT_APPROVED` | `report.updated` |
| 반려 | `REPORT_REJECTED` | `report.updated` |
| 삭제 | `REPORT_DELETED` | `report.updated` |

감사 로그에는 report ID, 이전·이후 상태, 버전 번호, 결과와 안전한 사유만 저장한다. 보고서 본문 전체와 통계 snapshot을 감사 로그에 복제하지 않는다.

## 7. 구현 인수 조건

- 허용 전이 전체와 표에 없는 전이 거부 테스트
- 승인된 보고서 수정·삭제 거부 테스트
- 반려 후 수정 시 새 버전과 `DRAFT` 전환 테스트
- 최신 버전 작성자의 자기 승인 거부 테스트
- `expectedVersionNo` 경합 테스트
- 상태 변경, 승인자·시각, 감사 로그의 트랜잭션 정합성 테스트
- 상태별 역할·권한 매트릭스 테스트
