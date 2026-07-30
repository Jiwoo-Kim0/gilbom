# 팀원 B Story - Subtask 진행 현황

- 기준일: 2026-07-28
- 범위: 로봇 MQTT 통신, 텔레메트리, 실시간 관제, 명령, 임무 백엔드
- 계획 기준: 최상위 `AGENTS.md`의 팀원 B 12일 계획
- 상태 기준:
  - `DONE`: 구현과 자동 검증이 끝난 범위
  - `IN PROGRESS`: 일부 구현됐지만 통합 검증 또는 핵심 하위 작업이 남은 범위
  - `TODO`: 아직 구현 또는 검증을 시작하지 않은 범위
  - `WAITING`: 임베디드팀 또는 팀원 C의 확인이 필요한 범위

> `참조용.agents.md`의 체크박스는 단계별 계획과 최종 확인 항목이 중복되어 있으므로,
> 단순 체크박스 개수 대신 실제 코드, 테스트, Flyway 적용 결과를 기준으로 다시 분류했다.

## 0. 판정에 사용한 문서 우선순위

| 우선순위 | 문서 | 적용 기준 |
|---:|---|---|
| 1 | [최상위 AGENTS.md](../../AGENTS.md) | 팀원 B의 책임 경계, 12일 구현 순서, P0·P1·P2 범위를 정하는 포괄 계획서 |
| 2 | [OpenAPI](openapi-v1.yaml), [권한 매트릭스](authorization-matrix.md) | REST·SSE 경로, DTO, 오류, Permission과 Robot 객체 범위의 수용 기준 |
| 3 | [MQTT v2 정본](final-mqtt-topic-schema-v2.md), [MQTT-DB 매핑](mqtt-device-db-mapping.md) | Topic·Payload·`mqtt_id`·`robot_id`·`boot_id`·`seq`·TTL·Command 계약 |
| 4 | [오늘 해야 할 일](../오늘%20해야할%20일.md), [참조용 작업서](../참조용.agents.md) | 오늘 실행 순서, 세부 체크리스트, 작업 기록 |
| 5 | Backend 코드·Flyway·자동 테스트 | 문서에 적힌 기능이 실제로 구현되고 검증됐는지 판정하는 최종 근거 |

문서가 충돌하면 최상위 계획서의 책임과 일정 범위를 우선한다. API·MQTT의
구체적인 형식은 `web/docs` 명세를 따르며, 완료 표시는 실제 구현과 검증 근거가
있을 때만 한다.

## 1. Jira 8건 기준 요약

| 상태 | 건수 | Jira |
|---|---:|---|
| 완료 | 6 | `S15P11D101-366`, `S15P11D101-367`, `S15P11D101-375`, `S15P11D101-376`, `S15P11D101-377`, `S15P11D101-378` |
| 진행 중 | 0 | - |
| 해야 함 | 2 | `S15P11D101-368`, `S15P11D101-379` |

완료율은 Jira Story 건수 기준 `6 / 8`이다. 다만 Story의 크기가 서로 달라 이
수치를 개발 진척률로 그대로 해석하면 안 된다.

### 12일 계획과의 관계

아래 8건은 “Day 12에 한 번에 하는 일”이 아니라, 최상위 계획의 Day 1부터
Day 9까지 흩어진 팀원 B 백로그다.

| 계획 일자 | 최상위 계획의 목표 | 연결 Jira |
|---|---|---|
| Day 1~2 | MQTT 계약, Robot 식별자, DB·MQTT 골격 | `366` |
| Day 3 | Telemetry 저장, 현재 상태, 조회 API | `368`, `375`, `376`, `378` 일부 |
| Day 4 | SSE, 실시간 상태, 온라인·오프라인 | `367`, `378` |
| Day 5 | 순찰 구역·임무·진행률·궤적 | `379` |
| Day 6 | 명령 발행, ACK, 만료·중복 방지 | `377` |
| Day 7~8 | 실제 Jetson, 단절·재연결, 지연·중복·Timeout 안정화 | `375`, `377`, `378` |
| Day 9 | 관제 대시보드·임무 진행률 UX 완성 | `376`, `379`의 프론트 후속 |

## 2. Jira 상세 현황표

| Jira | 상세 범위 | 상태 | 현재 근거 | 완료를 위해 남은 것 |
|---|---|---|---|---|
| `S15P11D101-366` MQTT 식별자와 DB 키 구조 정합 | DB 내부 키는 `robot_id UUID`, 외부 MQTT 식별자는 `mqtt_id UNIQUE NOT NULL`로 분리한다. Topic 경로·Payload `mqtt_id`·MQTT username은 같은 값을 쓰고 자격증명은 `mqtt_client_id`, `mqtt_password`로 명명한다. | **DONE** | V15 Migration, MQTT DTO·Topic parser·Publisher·Mock Robot, 등록·자격증명·조회 응답이 `robotId`와 `mqttId`를 구분한다. | 완료 유지 조건은 신규 MQTT DTO와 문서에서 외부 식별자를 `robot_id`로 되돌리지 않는 것이다. |
| `S15P11D101-367` Robot 단방향 통신 SSE 구조 API | REST CRUD가 아니라 서버→브라우저 단방향 Event Stream이다. 인증·`ROBOT_STATUS_VIEW`·Robot 객체 범위를 적용하고 Telemetry 갱신 Event, Heartbeat, 연결 정리를 제공한다. 최신 상태의 정본은 REST이며 재연결 시 REST를 다시 조회한다. | **DONE (Backend MVP)** | `/api/v1/stream/robots`, Emitter Registry, Heartbeat, Telemetry commit 이후 Event, Scope 통합 테스트, OpenAPI가 있다. | 브라우저 연결과 다중 Backend Fan-out·Replay는 별도 후속 범위이며, 현재 Backend MVP 완료 판정을 막지 않는다. |
| `S15P11D101-368` Telemetry REST API | “CRUD”로 만들지 않는다. Telemetry 생성은 MQTT Consumer만 담당하고 수정·삭제는 금지한다. REST가 필요하면 현재값과 이력을 읽는 `GET current`, `GET history`로 한정한다. | **TODO / 범위 재정의 필요** | 현재 Robot 목록·지도·요약·단건에서 최신 Telemetry 일부를 조회할 수 있으나 Telemetry 전용 이력 API는 없다. | `376`에 병합할지 별도 Story로 둘지 결정하고, 별도라면 기간 UTC `[from,to)`, 0-based 페이지, 최대 100, Permission·SQL Scope·404·OpenAPI·계약 테스트를 구현한다. |
| `S15P11D101-375` Telemetry 저장과 현재 상태 갱신 | MQTT Telemetry를 검증해 이력 INSERT와 현재 상태 UPSERT를 같은 트랜잭션에서 수행한다. `observed_at`과 `received_at`을 분리하고 오래된 값이 최신 상태를 덮지 못하게 한다. | **DONE** | V17로 `seq>=1`을 강제하고, 중복·지연·비활성 Robot·Rollback·SSE 통합 테스트와 TLS Mosquitto Mock E2E를 통과했다. 이전 Boot Replay 차단도 `378`과 함께 검증했다. | 실제 Jetson 실기 연결은 Day 7 통합 범위다. |
| `S15P11D101-376` Robot 관제 조회 REST API | 목록·요약·지도·단건을 제공한다. 목록·집계는 SQL `JOIN/EXISTS`에서 객체 범위를 제한하며 범위 밖 단건은 존재하지 않는 대상과 동일한 `404`를 반환한다. | **DONE** | `/api/v1/robots`, `/summary`, `/map`, `/{robotId}`, `ROBOT_STATUS_VIEW`, SQL Scope, Controller·Scope 통합 테스트, OpenAPI가 있다. | 프론트 연결은 사용자가 현재 제외했으므로 이 Backend Story 완료 판정에 포함하지 않는다. |
| `S15P11D101-377` Robot 명령·ACK 생명주기와 REST API | REST에서 명령을 생성·조회하고 DB 저장 후 MQTT로 발행한다. `command_id` 멱등성, 만료시간, Retain 금지, ACK/Result 저장, `CREATED→SENT→ACCEPTED→COMPLETED` 및 실패·거절·만료 전이를 관리한다. | **DONE (Backend·Frontend·정상 E2E)** | V21 Command·Result Table, REST·MQTT Publish·ACK·Timeout·SSE·Permission·SQL Scope와 Frontend 여섯 제어 버튼·확인 Modal·상태/Result 상세를 구현했다. 실제 TLS Broker·Mock Robot·임시 DB·Backend·SSE·React에서 여섯 정상 명령과 각 `ACCEPTED→COMPLETED` Result를 Browser/DB로 검증했으며 Frontend lint·build와 Node 테스트 24건이 통과했다. | 실패·거절·만료·중복·늦은 Result와 권한·Scope·CSRF·감사 Browser E2E, 실제 Jetson·EC2 재검증은 후속 통합 범위다. |
| `S15P11D101-378` Presence·State·Boot Session 저장 | Presence로 연결·단절과 Last Will을 기록하고 State로 mode·health를 저장한다. `boot_id`별 Session과 `seq` 진행을 추적해 재부팅과 Replay를 구분하고 변경을 SSE로 알린다. | **DONE** | V18 Boot Session·append-only Status History, Presence·State Service/Handler, 90초 Timeout과 commit 이후 SSE를 구현했다. `S15P11D101-432`에서 State·Telemetry·Event 공용 `seq`, 새 Boot `seq=1`, 종료·이전 Boot Replay 차단을 보강했다. | 실제 Jetson 단절·재연결 실기와 다중 Backend SSE Fan-out은 Day 7~10 후속 통합 범위다. |
| `S15P11D101-379` 순찰 임무와 경로 관리 | 순찰 구역·경로 CRUD, Mission 생성·Robot 배정, 상태 전이·이력, 시간 기반 진행률과 실제 궤적을 관리하고 명령 수명주기와 연결한다. | **DONE** | V22~V25·V32 정본·FK·Robot별 활성 Mission 제약, Area 등록·변경·보관 REST, 내부 Dummy Route, 시간 기반 Mission·진행률·ETA, Area·Trajectory·현재 위치 지도, START·PAUSE·RESUME·CANCEL Command FK와 OpenAPI·통합·Browser 테스트를 구현했다. 실제 로그인·MFA Browser 체인과 TLS MQTT·Mock Robot·DB·REST/SSE·React Mission 전체 E2E도 통과했다. | 충전소 복귀·충전 후 재개는 구현 범위에서 제외했으며 실제 Jetson·EC2 재검증은 Day 7~10 통합 범위다. |
| `S15P11D101-432` `boot_id`·`seq` Replay 정책 고정 | State·Telemetry·Event 4종의 Boot별 공용 순번, 정확한 재전송 멱등성, 교차 Topic 충돌과 종료·이전 Boot 차단을 하나의 정책으로 적용한다. | **DONE (Backend·Mock)** | 공통 Replay Service가 Current State 행 잠금 아래 세 History Table을 함께 검사한다. 새 Boot `seq=1`, 증가 순번·누락 허용, `REPLACED_BY_NEW_BOOT`, 복구 가능한 Presence 재연결, 제한 Metric과 오류 코드가 구현됐다. 실제 로컬 TLS Broker에서 정상 Event의 정확한 재전송 멱등성과 같은 `(boot_id,seq)`의 다른 Event 충돌 거부를 검증했다. | 실제 Jetson Publisher와 EC2 Broker 재검증은 Day 7 통합 범위다. |
| `S15P11D101-434` 기기 Credential 회전·폐기 API | 기존 발급 API가 새 Credential 버전을 회전하고, Secret 없는 이력 조회와 ACTIVE Credential 즉시 폐기를 제공한다. | **DONE (Backend)** | Robot 잠금 기반 버전 직렬화, 기존 버전 `REVOKED(ROTATED)`, Broker hash 일치 검증, DB 실패 시 이전 hash 복구, 필수 감사, 반복 폐기 멱등성, 권한·CSRF를 구현했다. `TODAY-B-00-6`에서 Registry Drift 조회·복구와 실제 TLS Cutover까지 검증했다. | EC2 운영 Broker와 실제 Jetson의 Cutover는 배포·장비 연동 단계에서 최종 확인한다. |

## 3. Story별 완료 조건

### `S15P11D101-368` Telemetry 조회 REST

- 생성은 MQTT만 허용하고 REST `POST`, `PATCH`, `DELETE`는 만들지 않는다.
- 별도 구현 시 `GET /api/v1/robots/{robotId}/telemetry/current`와
  `GET /api/v1/robots/{robotId}/telemetry`를 기본 계약으로 삼는다.
- 기간은 UTC `[from,to)`, 페이지는 0-based, `size` 최대 100으로 제한한다.
- `ROBOT_STATUS_VIEW`와 SQL Robot Scope를 적용하고 범위 밖 단건은 `404`다.
- OpenAPI DTO·오류 코드·권한 매트릭스·계약 테스트를 같은 작업에서 갱신한다.

### `S15P11D101-375` Telemetry 저장

- 정상 Telemetry 1건이 History에 한 번 저장되고 Current State를 갱신한다.
- 동일한 `boot_id + seq` 재수신이 History를 중복 생성하지 않는다.
- 더 오래된 `observed_at` Event는 History 정책에 따라 기록할 수 있지만 Current
  State를 덮지 않는다.
- Topic의 `mqtt_id`와 Payload의 `mqtt_id`가 다르거나 Robot이 미등록이면 거절한다.
- History INSERT와 Current UPSERT 중 하나가 실패하면 둘 다 Rollback한다.
- DB 반영 이후에만 SSE Event를 발행한다.

### `S15P11D101-377` Command·ACK

- 상태 변경에는 `ROBOT_COMMAND_EXECUTE`와 대상 Robot 객체 범위를 모두 적용한다.
- 명령 생성과 감사 기록, 최초 상태 저장은 한 트랜잭션으로 처리한다.
- DB 저장 후 MQTT Publish 결과에 따라 `SENT` 또는 `FAILED`로 전이한다.
- 동일 `command_id`는 같은 결과를 반환하며 로봇에서 중복 실행되지 않는다.
- ACK/Result는 허용된 상태 전이만 적용하고 늦은 Result를 별도 기록한다.
- 만료된 미완료 명령은 Scheduler가 `EXPIRED`로 바꾸고 조회 API에서 확인된다.
- 정상·실패·거절·만료·중복·범위 밖 요청을 자동 테스트한다.

### `S15P11D101-378` Presence·State·Boot

- Presence ONLINE/OFFLINE과 `last_seen_at`이 저장된다.
- Grace Period 이후 Heartbeat가 없거나 Last Will을 받으면 OFFLINE으로 판정한다.
- `boot_id`가 바뀌면 새 Boot Session을 열고 첫 State·Telemetry·Event의 `seq=1`만 허용한다.
- 같은 Boot Session에서 새 `seq`는 이전 값보다 커야 하며 누락은 허용한다.
- State·Telemetry·Event 사이에서 이미 처리한 `seq`를 다른 메시지가 재사용하면 충돌로 거절하고, 정확히 같은 재전송만 멱등 처리한다.
- State의 mode·health가 Current State와 Status History에 반영된다.
- Presence·State 변경은 Transaction commit 이후 Scope가 허용된 SSE 연결에만 전달된다.

### `S15P11D101-379` Mission·Patrol

- 순찰 구역과 경로를 생성·조회·수정·비활성화할 수 있다.
- Mission은 Robot UUID와 연결되고 허용된 상태 전이만 가능하다.
- Mission 상태 변경과 이력이 같은 트랜잭션으로 저장된다.
- Telemetry 위치로 실제 궤적을 저장하고, 진행률은 최신
  `mission.elapsed_sec / planned_duration_sec`로 계산한다.
- 시작·일시정지·재개·종료·긴급정지는 `377`의 Command를 통해 수행한다.
- 사용자 Permission과 Robot 객체 범위를 적용하고 범위 밖 단건은 `404`다.

---

## 4. 내부 작업 패키지 → Subtask

아래 12개는 Jira 8건을 구현·검증하기 위한 내부 작업 패키지다. Jira 건수와
직접 합산하지 않는다.

- [x] **B-STORY-01. MQTT 식별자와 DB 키 구조 정합성** — `DONE`
  - [x] 외부 MQTT 식별자를 `mqtt_id`로 통일
  - [x] 내부 DB 기본키를 `robot.robot_id UUID`로 유지
  - [x] 다른 테이블이 `robot_id UUID`를 외래키로 참조하도록 확인
  - [x] `mqtt_id`에 `UNIQUE NOT NULL` 제약 적용
  - [x] Topic의 `{mqttId}`와 Payload의 `mqtt_id` 일치 검증
  - [x] MQTT DTO 8종, parser, handler, publisher, Mock Robot 반영
  - [x] Flyway V13~V15 적용 확인
  - [x] Backend 전체 테스트와 Docker 기동 확인
  - [x] `user_robot_assignment`, `robot_credential`, `robot_current_state`, `robot_telemetry`의 `robot_id` 외래키 확인

- [x] **B-STORY-02. 텔레메트리 저장과 현재 상태 갱신** — `DONE`
  - [x] MQTT Telemetry 수신과 필수 필드 검증
  - [x] `mqtt_id`로 내부 `robot_id UUID` 조회
  - [x] 텔레메트리 이력 INSERT
  - [x] 로봇 현재 상태 UPSERT
  - [x] `observed_at`과 `received_at` 분리
  - [x] 오래된 Telemetry의 최신 상태 덮어쓰기 방지
  - [x] 트랜잭션 커밋 후 SSE 이벤트 발행
  - [x] 서비스·Repository 자동 테스트
  - [x] 변경된 `mqtt_id` 계약으로 실제 Broker·DB E2E 재검증
  - [x] 중복 Event의 History 중복 저장 방지 검증
  - [x] 오래된 Event의 Current State 비갱신 DB 검증
  - [x] 미등록 Robot·잘못된 Payload·부분 실패 Rollback 검증

- [x] **B-STORY-03. Robot 관제 조회 REST API** — `DONE`
  - [x] 로봇 목록 조회
  - [x] 대시보드 요약 조회
  - [x] 지도용 로봇 상태 조회
  - [x] 로봇 상세 조회
  - [x] 사용자별 Robot Scope를 SQL 조회 단계에서 제한
  - [x] 관리자·일반 사용자 권한 분리
  - [x] OpenAPI 명세 반영
  - [x] Controller 및 Scope 통합 테스트

- [x] **B-STORY-04. Robot 실시간 관제 SSE 백엔드 MVP** — `DONE`
  - [x] SSE 구독 Endpoint
  - [x] 사용자·로봇 Scope 검증
  - [x] Emitter 등록·정리
  - [x] Heartbeat와 Callback 처리
  - [x] Telemetry 갱신 이벤트 발행
  - [x] 단일 Backend 인스턴스 기준 자동 테스트
  - [x] 운영 환경·브라우저 실수신 검증 범위를 `B-STORY-06`으로 분리

- [ ] **B-STORY-05. MQTT v2 계약 최종 확정** — `IN PROGRESS / WAITING`
  - [x] Topic namespace와 `mqtt_id` 규칙 확정
  - [x] 공통 Envelope 필드 정의
  - [x] 시간·좌표·재전송 기본 규칙 정의
  - [x] Telemetry·State·Presence·Command·Result Payload 초안
  - [x] Event `ttl_sec` 60~300초와 `occurred_at` 기준 만료 처리 확정
  - [x] Backend의 `boot_id`, 공용 `seq`, 재시도 ID Replay 정책 고정 (`S15P11D101-432`)
  - [x] Embedded Event Wire Payload의 `mqtt_id`·`ttl_sec`와 재전송 불변 필드 자동 검증
  - [x] 로컬 TLS Broker에서 Event 만료·미래시각·정확한 재전송·순번 충돌 DB E2E
  - [ ] `(WAITING)` Embedded 구현이 UUIDv4 Boot·공용 `seq`·재시도 ID 정책을 따르는지 확인
  - [ ] `(WAITING)` Embedded의 NTP 오차와 발행 주기 확인
  - [ ] `(WAITING)` MQTT Client ID와 동시 접속 정책 확인
  - [ ] `(WAITING)` 팀원 C의 Incident, Image, bbox 계약 확인
  - [ ] 미합의 항목마다 담당자와 완료 예정일 기록

- [ ] **B-STORY-06. Mock Robot 전체 통합 검증** — `IN PROGRESS`
  - [x] Mock Robot 코드에 `mqtt_id` 계약 반영
  - [x] Backend MQTT parser와 handler 자동 테스트
  - [x] TLS·ACL·계정 구성
  - [x] 변경된 `mqtt_id` 계약으로 Presence 발행 재검증
  - [ ] State 수신·DB 반영 재검증
  - [x] Telemetry 수신·DB 반영 재검증
  - [x] 중복 이벤트의 이력 중복 저장 방지 검증
  - [x] Event 만료·미래시각·정확한 재전송·순번 충돌의 TLS Broker→DB 검증
  - [x] 오래된 Telemetry의 현재 상태 덮어쓰기 방지 DB 검증
  - [ ] 미등록 `mqtt_id`와 ACL 위반 거절 검증
  - [ ] Command 발행 → Mock 수신 → Result 수신 검증
  - [ ] SSE Test Client 또는 브라우저 실수신 검증

- [x] **B-STORY-07. 로봇 명령·ACK 수명주기** — `DONE (Backend)`
  - [x] Command MQTT Publisher 경로
  - [x] Command Result Topic parser와 수신 handler 경로
  - [x] Command·Result Payload의 `mqtt_id` 계약 반영
  - [x] `robot_command` Table
  - [x] `robot_command_result` ACK·Result Table
  - [x] 명령 생성 REST API
  - [x] `Idempotency-Key`, `command_id`, 만료시간, 중복 발행 방지
  - [x] `CREATED → SENT → ACCEPTED → COMPLETED` 상태 전이
  - [x] `FAILED`, `REJECTED`, `EXPIRED`, `PUBLISH_FAILED` 처리
  - [x] ACK 저장과 Timeout Scheduler
  - [x] 명령 목록·단건·Result 조회 API
  - [x] 정상·실패·거절·만료·지연·중복·범위 밖·Rollback 통합 테스트
  - [x] Frontend Command Type·API·권한·Robot 상세 Route
  - [x] 상태 기반 제어 버튼·중요 명령 확인 Modal·중복 제출 차단
  - [x] ACK·실패·Timeout 상태 화면과 SSE 정본 재조회
  - [x] 실제 Mock Robot 정상 Broker·Browser E2E
  - [ ] 실패·거절·만료·중복·늦은 Result와 보안 Browser E2E

- [ ] **B-STORY-08. MQTT 계약 검증 산출물** — `TODO`
  - [ ] `docs/asyncapi-mqtt-v2.yaml`
  - [ ] 공통 Envelope JSON Schema
  - [ ] Presence·State·Telemetry JSON Schema
  - [ ] Event·Mission·Command·Result JSON Schema
  - [ ] 정상 Fixture
  - [ ] 필수 필드 누락, 잘못된 Enum, UUID, Timestamp Fixture
  - [ ] Topic·Payload `mqtt_id` 불일치 Fixture
  - [ ] 중복·오래된 데이터·만료 명령 Fixture
  - [ ] 각 Fixture의 수락 여부, 오류 코드, DB 기대 결과

- [x] **B-STORY-09. Presence·State·Boot Session 저장** — `DONE`
  - [x] Presence 수신 시 ONLINE·OFFLINE 상태 저장
  - [x] State 수신 시 Mode·Health 저장
  - [x] `robot_status_history` append-only Table
  - [x] `robot_boot_session` Table
  - [x] Boot 변경 시 `seq=1` 재시작 처리
  - [x] Presence·State 변경 SSE 발행
  - [x] 비정상 종료와 Last Will 처리
  - [x] Offline 판정 90초·Scan 30초와 stale 기준 확정

- [ ] **B-STORY-10. 순찰 임무와 경로 관리** — `IN PROGRESS`
  - [x] `patrol_area` Table과 등록·조회·이름/경계 변경·보관 REST
  - [x] `patrol_route` 경로 저장 구조와 Area별 조회 REST
  - [x] Route 이름·Geometry 변경은 새 버전 등록, 과거 행 수정·삭제와 수동 상태 API는 미제공
  - [x] `mission` Table과 생성·목록·상세·변경·궤적·이력 REST
  - [x] Mission과 `robot_id UUID` 외래키 연결
  - [x] Mission MQTT 상태 전이와 append-only 이력
  - [x] `mission.elapsed_sec / planned_duration_sec` 기반 단조 증가 진행률 계산
  - [x] Telemetry에 Mission UUID를 연결한 실제 순찰 궤적 저장
  - [x] 시작·일시정지·재개·종료 명령 연동 — START·PAUSE·RESUME·CANCEL FK 연결 완료
  - [x] Area·실제 Trajectory·현재 위치 지도와 영역 이탈 경고
  - [x] 실제 로그인 Backend Browser 단일 체인과 Mission 전체 Mock E2E
  - 충전소 복귀·충전·충전 후 재개는 구현 범위 제외

- [ ] **B-STORY-11. 실제 Jetson 통합** — `TODO / WAITING`
  - [ ] `(WAITING)` 실제 Jetson 연결 일정 확정
  - [ ] 실제 인증서·MQTT 계정으로 연결
  - [ ] Telemetry Payload 차이 확인
  - [ ] 시간 동기화와 GPS 값 검증
  - [ ] 네트워크 단절·재연결
  - [ ] 중복·지연·Outbox 재전송
  - [ ] 명령 수신과 Result 반환
  - [ ] 실제 데이터 부하와 Burst 확인

- [ ] **B-STORY-12. 결과 문서화와 Jira 인계** — `TODO`
  - [ ] 로컬 실행 순서와 환경변수 문서화
  - [ ] Mock Robot 실행 명령 문서화
  - [ ] DB 확인 SQL과 E2E 결과 기록
  - [ ] 실패 사례와 해결 방법 기록
  - [ ] Story별 Subtask와 산출물 링크 연결
  - [ ] 외부팀 확인 항목을 `WAITING`으로 분리
  - [ ] 실제 Jetson 통합을 별도 Jira Story로 등록
  - [ ] SSE 다중 인스턴스·재연결 보강을 후속 Story로 등록
  - [ ] Secret, 인증서, 비밀번호가 문서에 없는지 확인

---

## 5. 우선순위와 다음 작업

### 오늘 P0

1. `375`의 중복·지연·미등록·Rollback DB 통합 테스트와 Mock Broker E2E를 끝낸다.
2. `378`의 Presence·State 저장, Online·Offline 판정 완료 상태를 유지한다.
3. `377`의 Command DB·ACK·Timeout 완료 상태를 유지하고 Mock Broker E2E를 후속 검증한다.
4. 다음으로 `379`의 Patrol Area·Mission 최소 Schema와 CRUD를 구현한다.
5. `368`은 `376` 병합 여부를 결정한 뒤 필요할 때만 읽기 전용 History API로 만든다.

최상위 계획의 날짜 순서만 따르면 `379`(Day 5)가 `377`(Day 6)보다 먼저다.
다만 P0 중간 게이트가 “명령 Publish와 ACK 확인”을 요구하므로, 현재 구현 위험도는
`375 → 378 → 377 → 379` 순서로 낮추는 것이 안전하다.

### 계약·검증 병행 작업

1. `B-STORY-05`의 미확정 질문을 Embedded·팀원 C에 전달한다.
2. `B-STORY-08`의 AsyncAPI, JSON Schema, Fixture를 만든다.
3. 변경된 `mqtt_id` 계약으로 `B-STORY-06` Mock Robot E2E를 다시 실행한다.
4. 검증 결과를 `B-STORY-12` Runbook과 Jira Subtask에 연결한다.

### 현재 범위에서 제외

- Robot Frontend 화면 구현
- 팀원 C 소유의 탐지 이벤트 내용 처리와 결함 병합
- 팀원 A 소유의 로그인 구조, 배포, 보고서

위 항목은 연동 계약은 확인하되 팀원 B의 완료율에는 포함하지 않는다.

## 6. Day 12 마감 현황판

| 마감 | 완료 대상 | 현재 상태 | 지연 시 조치 |
|---|---|---|---|
| Day 1 | `375` Telemetry 안정화 | DONE | 300개 자동 테스트와 TLS Mosquitto Mock E2E 완료 |
| Day 2 | `378` Presence·State·Boot | DONE | 308개 자동 테스트와 TLS Mosquitto Mock E2E 완료 |
| Day 4 | `377` Command·ACK·Timeout | DONE (Backend) | 실제 Robot·Mock Broker 왕복 E2E는 Day 10 통합에서 재검증 |
| Day 6 | `379` Mission·Patrol | DONE | Mission REST·시간 진행률·지도·명령 FK와 실제 로그인 Browser 체인, TLS MQTT·Mock Robot 기반 Mission 전체 E2E 완료. 충전 기능은 범위에서 제외함 |
| Day 7 | `368` 정리, 계약·Fixture | TODO | 별도 API를 `376`에 병합 |
| Day 9 | 팀원 B P0 기능 동결 | 미착수 범위 있음 | P1·P2 제거, 신규 기능 금지 |
| Day 10 | 실기·통합 시험 | 예정 | Jetson 불가 시 동일 계약의 Mock과 증거 영상 사용 |
| Day 11 | 안정화 | 예정 | 치명·높은 우선순위 Bug만 수정 |
| Day 12 | 최종 검증·시연 | 예정 | P0 실패가 있으면 해당 흐름만 집중 수정·재시험 |

현재 7건 완료 상태이므로, Day 7에 남은 Jira 1건을 정리하고 Day 8~9에
프론트를 연결하면 Day 10~12의 검증 시간을 보존할 수 있다. 이 일정은 P0를 고정하고
`368` 중복 API, 복잡한 Dashboard, 다중 Instance SSE와 고급 Mission UX를 후속으로
이동할 수 있다는 조건에서 달성 가능하다.
