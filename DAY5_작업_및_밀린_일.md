# Day 5 작업 및 밀린 일

- 기준 시각: 2026-07-29 02:32 (Asia/Seoul)
- 담당 범위: 팀원 B — 로봇 통신·임무·실시간 관제
- 계획 기준: `AGENTS.md`의 Day 1~5
- 상태 근거: 최신 `CHANGLOGS/`, Backend 코드·테스트, `web/docs/2026-07-28-story-subtask-status.md`

## 1. 현재 상태 요약

- Day 5 전체 진행률: 약 70~75%
- Backend 진행률: 약 90%
- Backend 최신 전체 검증: 92 Suite, 423 Test, 실패·오류·생략 0
- 현재 Branch: `minho`
- `origin/minho`보다 로컬이 1 Commit 앞서 있으며, 순찰 경로 등록 Commit은 아직 원격에 Push되지 않았다.
- 가장 큰 미완료 범위는 Mission·Patrol Frontend, 브라우저 SSE 연동, 순찰 구역·경로의 변경·보관 API다.

## 2. Day 5 완료된 작업

- [x] 순찰 구역 목록·단건 조회
- [x] 순찰 구역 Polygon·MultiPolygon 등록
- [x] 순찰 구역 PostGIS 유효성·좌표·자가 교차 검증
- [x] 순찰 경로 목록·단건 조회
- [x] LineString·MultiLineString 경로 등록
- [x] 동일 경로 코드의 버전 자동 증가
- [x] 새 활성 경로 등록 시 기존 활성 버전 자동 비활성화
- [x] Mission 생성·목록·상세·부분 변경 REST
- [x] Mission 상태 이력·감사 로그
- [x] Telemetry와 Mission UUID 연결
- [x] 실제 GPS 궤적 조회
- [x] Route 투영 기반 단조 증가 진행률 계산
- [x] 진행률 기반 예상 완료 시각 계산
- [x] START·PAUSE·RESUME·CANCEL Command에 Mission·Area FK 연결
- [x] OpenAPI와 Backend 통합 테스트

## 3. Day 5 오늘 해야 할 작업

### P0-1. 순찰 구역·경로 관리 Backend 마감

- [ ] 순찰 구역 이름·경계 수정 API
- [ ] 순찰 구역 `ACTIVE → INACTIVE → ARCHIVED` 상태 전이 API
- [ ] 사용 중인 순찰 구역의 수정·보관 제한 규칙 확정
- [ ] 순찰 경로 변경을 새 버전 등록으로만 처리할지 PATCH도 허용할지 확정
- [ ] 순찰 경로 수동 비활성화·보관 API 필요 여부 확정
- [ ] 변경 작업의 감사 행위와 Audit Snapshot 추가
- [ ] Controller·PostGIS 통합 테스트 및 OpenAPI 동기화

완료 기준:

- 등록·조회·수정·보관 정책이 API와 OpenAPI에서 일치한다.
- 과거 Mission이 참조하는 Area·Route 행은 삭제하지 않는다.
- 공간 검증, 권한, CSRF, 감사 로그, Rollback을 자동 테스트한다.

### P0-2. Mission 관리 Frontend

- [ ] `mission` Feature 폴더와 API Client·Type 구성
- [ ] `MISSION_VIEW`, `MISSION_MANAGE` 권한 적용
- [ ] Mission 목록과 Robot·Area·Route·상태·기간 필터
- [ ] Mission 생성 Form
- [ ] Mission 상세와 상태 이력
- [ ] 진행률과 예상 완료 시각 표시
- [ ] 허용된 상태에 맞는 시작·일시정지·재개·취소 버튼
- [ ] API 오류·권한 없음·빈 데이터·로딩 상태 처리

완료 기준:

- 관리자가 화면에서 Area·Route·Robot을 선택해 Mission을 생성할 수 있다.
- Mission 상세에서 현재 상태, 진행률, 예상 완료 시각, 이력을 확인할 수 있다.
- 권한이 없거나 허용되지 않은 상태의 제어 버튼은 노출·실행되지 않는다.

### P0-3. 지도와 순찰 궤적 Frontend

- [ ] 지도에서 순찰 구역 Polygon·MultiPolygon 표시
- [ ] 지도에서 계획 Route 표시
- [ ] 실제 Telemetry 궤적 표시
- [ ] 현재 Robot GPS Marker 표시
- [ ] 계획 경로와 실제 궤적을 서로 다른 색으로 구분
- [ ] 진행률 또는 완료·미완료 구간 표시
- [ ] 지도에서 구역·경로를 그려 GeoJSON 등록 API와 연결
- [ ] GeoJSON 좌표를 `[longitude, latitude]` 순서로 전송

완료 기준:

- 구역 → 경로 → Mission → 실제 궤적의 관계를 한 화면에서 확인할 수 있다.
- 새로 들어온 Telemetry에 따라 Marker와 궤적이 갱신된다.

### P0-4. 브라우저 종단 간 검증

- [ ] 순찰 구역 등록 → 경로 등록 → Mission 생성
- [ ] Mission 시작 명령 생성 및 상태 확인
- [ ] Mock Telemetry 수신 후 진행률·예상 완료 시각 확인
- [ ] Mission 완료 Event 후 진행률 100%와 상태 이력 확인
- [ ] SSE 연결·Heartbeat·재연결 확인
- [ ] 브라우저에서 위치·배터리·온도·마지막 수신 시각 실시간 갱신 확인
- [ ] 권한 없음, CSRF 실패, 잘못된 GeoJSON 오류 화면 확인

완료 기준:

- Mock Robot 데이터를 기준으로 MQTT → Backend → DB → REST/SSE → React 흐름이 한 번에 재현된다.
- 실패 시 Backend 오류 코드와 Frontend 안내 문구가 일치한다.

### P0-5. 검증·문서·공유

- [ ] Frontend `lint`와 `build`
- [ ] 변경된 Backend 영향 범위 테스트
- [ ] Backend 전체 테스트
- [ ] OpenAPI YAML Parse와 내부 `$ref` 검사
- [ ] `git diff --check`
- [ ] 새 CHANGELOG 작성 및 실제 변경 파일과 대조
- [ ] 진행 현황 문서의 Day 5 상태 갱신
- [ ] 로컬의 순찰 경로 Commit 검토 후 원격 Push 여부 결정

## 4. Day 1~4에서 밀린 일

### P0. Day 1 MQTT 계약 최종 동결

대화에서 합의했지만 진행 현황 문서에는 아직 반영되지 않은 내용:

- [ ] Telemetry 5초, State 상태 변경 즉시·30초 주기 확정 표시
- [x] `boot_id`는 통신 프로그램 시작 시 UUIDv4 생성
- [x] MQTT 재연결 시 같은 `boot_id` 유지
- [x] State·Telemetry·Event가 한 `boot_id` 안에서 공용 `seq` 사용
- [x] 새 `boot_id`에서 `seq=1`
- [x] 동일 Event 재전송 시 `event_id`, `boot_id`, `seq`, `occurred_at` 유지
- [x] 재전송 시 `sent_at`만 갱신할 수 있도록 명시

아직 최종 결정·외부 확인이 필요한 내용:

- [x] Event Payload `ttl_sec`를 60~300초로 사용하고 `occurred_at` 기준 만료 처리
- [ ] 로봇별 고정 MQTT Client ID와 중복 접속 시 기존 연결 종료 정책 확정
- [ ] 팀원 C와 Incident `image_id`, `bbox_norm`, 이미지 업로드 순서 확정
- [ ] 미합의 항목의 담당자와 확인 예정일 기록

완료 기준:

- MQTT 정본·DB 매핑·진행 현황 문서에 같은 규칙이 적혀 있다.
- Embedded·팀원 C가 확인한 버전 번호와 날짜가 기록돼 있다.

### P0. Day 3 관제 화면 연결

- [ ] Backend Robot 목록·요약·지도·단건 API를 기존 Robot Frontend에 연결
- [ ] 배터리·위치·온도·통신 상태 Card를 Mock 데이터에서 실제 API 데이터로 교체
- [ ] 최신 `observedAt`·`receivedAt`과 데이터 지연 상태 표시
- [ ] Telemetry 전용 History API를 별도 구현할지 기존 Robot 조회 API에 통합할지 결정

### P0. Day 4 실시간 관제 연결

- [ ] 브라우저 SSE Client 연결
- [ ] SSE Event 수신 시 Robot Card와 GPS Marker 갱신
- [ ] 90초 기준 ONLINE·OFFLINE 전환 표시
- [ ] SSE 끊김·재연결 시 REST 정본 재조회
- [ ] 브라우저 실제 수신과 Scope 제한 검증

### P1. Mock Robot 통합 검증 잔여

- [ ] State 수신과 DB 반영 재검증
- [ ] 미등록 `mqtt_id` 거부
- [ ] ACL 위반 발행·구독 거부
- [ ] Command 발행 → Mock Robot 수신 → Result 수신
- [ ] SSE Test Client 또는 브라우저 실수신

### P1. MQTT 계약 검증 산출물

- [ ] `asyncapi-mqtt-v2.yaml`
- [ ] 공통 Envelope JSON Schema
- [ ] Presence·State·Telemetry JSON Schema
- [ ] Event·Mission·Command·Result JSON Schema
- [ ] 정상·오류 Fixture
- [ ] 중복·지연·만료 기대 결과와 오류 코드

## 5. Day 5 실행 순서

1. MQTT 확정 내용을 진행 현황에 반영하고 팀원 C 확인 항목을 전달한다.
2. 순찰 구역 수정·보관과 경로 수명주기 정책을 마감한다.
3. Mission API Client·목록·생성·상세 화면을 연결한다.
4. 지도에 Area·Route·실제 궤적·Robot Marker를 연결한다.
5. SSE로 실시간 위치·상태 갱신을 연결한다.
6. Mock Robot 종단 간 시나리오를 실행한다.
7. 전체 테스트와 문서·CHANGELOG를 갱신한다.

## 6. Day 5에서 뒤로 미룰 작업

다음 항목은 Day 5 완료를 막지 않으며 원래 일정에 맞춰 후속 처리한다.

- 충전소 복귀와 충전 시작·완료 Event 계약: Day 6~8
- 실제 Jetson 연결·시간 동기화·연속 주행: Day 7~10
- 다중 Backend SSE Fan-out·Replay: P1 후속
- 운영 규모 Mission Query `EXPLAIN`: 스테이징 데이터 준비 후
- 복잡한 통계 Chart와 고급 Mission UX: Day 9 또는 P2
- 운영 Runbook·Jira 최종 인계: Day 10~12

## 7. Day 5 종료 체크

- [ ] 순찰 구역과 경로의 Backend 관리 범위가 확정됐다.
- [ ] Mission 생성·상세·진행률·궤적 화면이 실제 API에 연결됐다.
- [ ] GPS Marker와 상태 Card가 SSE로 갱신된다.
- [ ] Mock Robot 종단 간 흐름을 재현했다.
- [ ] Backend 전체 테스트와 Frontend lint·build가 통과했다.
- [ ] 변경 내용과 검증 결과가 CHANGELOG에 기록됐다.
- [ ] 남은 외부 의존 작업의 담당자와 날짜가 기록됐다.
