# 실차 통합 미해결 이슈

- 기준 시각: 2026-08-06 (Asia/Seoul)
- 상태: 진행 중
- 최우선 목표: 좀비 Mission을 재현하고, 임무 취소·완료와 로봇 재할당이 항상 가능한 상태로 복구한다.

## 1. 우선순위

| 우선순위 | 원본 번호 | 이슈 | 현재 상태 |
| --- | ---: | --- | --- |
| P0-1 | 1 | 온라인이어도 임무 취소·완료가 되지 않는 좀비 Mission | 완료 경로 로컬 구현·자동 검증, Event 유실 복구·실차 E2E 남음 |
| P0-2 | 6 | 계획시간 또는 진행률 100% 도달 후 자동 종료되지 않음 | Online·최신 상태 기반 자동 완료 구현, Broker·실차 E2E 남음 |
| P1-1 | 2 | 이미지 전송 누락 시 송신·수신 로그 정합 확인이 어려움 | 재현·대조 필요 |
| P1-2 | 4 | 점자블록 미설치 탐지 사진을 전송할 통신 규격이 없음 | 로컬 구현·검증 완료, 배포·실차 E2E 필요 |
| P1-3 | 5 | 파손 탐지 시 사진이 촬영·전송되지 않음 | 1차 패치, 회귀 검증 필요 |
| P1-4 | 3 | 통신 코드 통합 후 주행·주차 및 장애물 우회가 정상 동작하지 않음 | 실차 재현 필요 |

## 2. P0-1 — 좀비 Mission 취소·완료 불가

### 현상

- 실제 Mission을 수행했는데 Backend Mission이 `READY`, `RUNNING` 또는 `PAUSED`에 남을 수 있다.
- Robot이 `ONLINE`이어도 임무 취소 또는 임무 완료가 처리되지 않는다.
- Robot Mode, Robot 내부 Mission 상태, Backend Mission 상태가 서로 달라져 화면과 명령 정책이 굳는 현상이 의심된다.
- 활성 Mission이 종료되지 않아 같은 Robot에 다음 Mission을 할당할 수 없다.

### 먼저 구분할 사항

- REST 요청이 `401 AUTH_UNAUTHENTICATED`이면 Mission 상태 문제가 아니라 관리자 세션 문제다. 재로그인 후 동일 요청을 다시 확인한다.
- 인증된 요청이 성공했는데도 DB 상태가 바뀌지 않거나, MQTT Command가 거절·Timeout·완료 후 Event 미수신으로 남을 때를 좀비 Mission 재현으로 본다.
- Robot의 `ONLINE`은 통신 가능성을 뜻할 뿐, `SAFE_STOP`, Robot Mission 상태 또는 Backend Mission 상태의 일치를 보장하지 않는다.

### 한 Mission에서 함께 수집할 증거

| 구간 | 확인 대상 | 반드시 남길 값 |
| --- | --- | --- |
| 관리자 요청 | Browser Network·Backend access log | HTTP 상태, request ID, 관리자 인증 여부, Mission ID |
| Backend 정본 | `robot.mission` | 상태, 진행률, 시작·종료 시각, 완료 사유, 갱신 시각 |
| Backend 현재 상태 | `telemetry.robot_current_state` | 연결 상태, Robot Mode, Mission 상태, 활성 Mission ID, 관측 시각 |
| 명령 흐름 | `robot.robot_command`, Command Result | Command ID, 명령 종류, 생성·발행·ACK·완료·Timeout 상태와 시각 |
| Mission Event | 원본 Event·`robot.mission_status_history` | Event ID, Event Type, Mission ID, 전이 전후 상태, 수신 시각 |
| Robot 로컬 상태 | Robot 로그·`mission.json`·Outbox | boot ID, seq, 로컬 Mission 상태, 발행 Event ID, Broker PUBACK·Receipt 여부 |

모든 로그는 같은 `mission_id`, `command_id`, `event_id`, `boot_id`와 UTC 시각으로 연결한다.

### 목표 동작

1. `READY`, `RUNNING`, `PAUSED` Mission은 관리자 취소로 `CANCELLED` Terminal 상태가 될 수 있어야 한다.
2. Robot이 `SAFE_STOP`이어도 Mission 취소는 가능해야 한다. 단, 취소가 긴급정지까지 자동 해제하면 안 된다.
3. 정상 완료는 Robot 정지와 `MISSION_COMPLETED` Event를 확인한 뒤 `COMPLETED·100%`로 확정한다.
4. Command Result 또는 Mission Event가 유실돼도 Mission이 무기한 활성 상태로 남지 않도록 Timeout과 관리자 복구 경로를 제공한다.
5. Terminal 전이는 멱등이어야 하며 동일 취소·완료 요청을 다시 보내도 중복 이력이나 역전 전이가 생기면 안 된다.
6. Terminal 전이와 함께 Robot의 활성 Mission 연결을 해제하고 다음 Mission 할당 후보로 다시 노출한다.

### 구현·검증 순서

1. 실제 Robot을 새로 등록해 한 번 재현한다.
2. 실제 Robot을 다른 팀이 사용 중이면 동일 시나리오를 Mock Robot으로 재현한다. 같은 MQTT ID로 실제 Robot과 Mock Robot을 동시에 연결하지 않는다.
3. 위 표의 여섯 구간을 한 타임라인으로 대조해 최초 불일치 지점을 찾는다.
4. Backend 상태 전이, 명령 정책, Robot Event 발행 중 원인이 된 경계를 수정한다.
5. Frontend 버튼 활성화 정책을 Backend의 허용 전이와 일치시킨다.
6. 아래 시나리오를 자동화한다.
   - `ONLINE + RUNNING` 취소
   - `ONLINE + SAFE_STOP + PAUSED` 취소
   - Robot Offline 상태에서 관리자 Terminal 복구
   - Command 완료 후 Mission Event 유실
   - 취소·완료 중복 요청

### 완료 기준

- 위 모든 시나리오에서 Mission이 무기한 활성 상태로 남지 않는다.
- 취소 성공 시 `CANCELLED`, 정상 완료 성공 시 `COMPLETED·100%`와 상태 이력이 DB에 남는다.
- 종료된 Mission을 가진 Robot이 새 Mission 할당 후보로 다시 표시된다.
- 실제 Robot 1회와 Mock Robot 회귀 테스트가 모두 통과한다.

## 3. P0-2 — 계획시간 도달 후 자동 완료되지 않음

### 현상

- 화면 진행률이 100%로 보이거나 계획시간이 끝났는데 Mission이 자동 종료되지 않는다.
- 현재 계약상 Backend는 완료 전 진행률을 최대 `99.99%`로 유지하고, 계획시간 도달 시 `COMPLETE_MISSION`을 발행한 뒤 Robot의 `MISSION_COMPLETED` Event로 완료를 확정한다. 화면 반올림 때문에 `100.0%`로 보일 가능성도 함께 확인한다.

### 목표 동작

1. `RUNNING` Mission이 `startedAt + plannedDurationSec`에 도달하면 Backend가 최대 5초 안에 `COMPLETE_MISSION`을 한 번 발행한다.
2. Robot은 주행을 안전하게 정지하고 Command Result와 `MISSION_COMPLETED` Event를 발행한다.
3. Backend는 Event 저장 트랜잭션에서 Mission을 `COMPLETED`, 진행률을 `100%`로 전이한다.
4. ACK 또는 Mission Event가 오지 않으면 재시도·Timeout을 기록하고 P0-1의 관리자 복구 경로를 제공해 좀비 Mission 생성을 막는다.
5. `PAUSED` Mission은 자동 완료하지 않고, 재개 후 계획시간이 지났으면 다음 검사에서 종료 흐름을 실행한다.

관리자의 수동 완료는 진행률 `100%`를 요구하지 않는다. 요청 시 Backend가
Online·최신 Telemetry·Robot Mode·Mission 일치를 확인하고 완료 명령을 보내며,
Robot의 `MISSION_COMPLETED` Event가 저장된 뒤에만 `COMPLETED·100%`로 확정한다.

### 완료 기준

- 60초 시험 Mission에서 계획시간 도달 후 종료 명령, Robot 정지, 완료 Event, DB `COMPLETED·100%`, UI 갱신이 하나의 타임라인으로 확인된다.
- 완료 Event 유실 시험에서도 Mission이 무기한 `RUNNING`으로 남지 않고 운영자가 복구할 수 있다.

## 4. P1-1 — 이미지 전송 누락 로그 정합

### 확인 방법

한 이미지마다 아래 식별자와 시각을 송신·수신 양쪽에서 같은 형식으로 남긴다.

| 단계 | 필수 로그 |
| --- | --- |
| Robot 촬영 | 원본 파일명, 촬영 시각, 해상도, 결함 종류·신뢰도 |
| Robot 업로드 | `event_id`, Robot UUID, 업로드 URL의 Host·Path, HTTP 상태, 응답 `image_id`, 바이트 크기, SHA-256 |
| Backend 수신 | request ID, Robot UUID, `event_id`, 검증 결과, 저장 `object_key`, `image_id`, 바이트 크기 |
| MQTT Incident | `event_id`, `evidence.image_id`, Broker PUBACK, Backend Receipt |
| DB 연결 | `storage.robot_image.image_id`와 `defect_observation.image_id` |
| 실제 파일 | Docker Volume의 `object_key`, 파일 크기, SHA-256 |
| 관리자 조회 | 서명 URL 발급 성공과 JPEG 응답 상태 |

### 완료 기준

- 정상 이미지 1장에 대해 Robot 파일과 Volume 파일의 크기·SHA-256가 일치한다.
- 동일 `event_id`와 `image_id`가 Robot 로그, Backend 로그, DB, MQTT Incident에 모두 나타난다.
- 업로드 실패 시에도 Incident Event는 `image_id=null`로 발행되고, 실패 원인이 Robot 로그에 명시된다.

## 5. P1-2 — 점자블록 미설치 탐지 이미지 계약

### 반영 상태

- 미설치는 파손과 동일한 `tbs/v2/robots/{mqtt_id}/events/incident` Topic으로 전송한다.
- `incident.defect_type=MISSING`으로 미설치를 구분한다.
- `DAMAGED`와 `MISSING` 모두 `incident.confidence`와 `evidence.bbox_norm`을 생략하거나 명시적 `null`로 전송할 수 있다.
- 값이 제공된 경우에만 `confidence` 범위와 `bbox_norm` 길이·좌표 순서를 검증한다. 값이 없으면 Backend에서 `null`로 처리하고 관리자 재검토 대상으로 저장한다.
- 두 유형 모두 JPEG를 먼저 업로드하고, 성공 시 반환된 UUID를 `evidence.image_id`로 전송한다. 업로드 실패 시에만 `image_id=null`을 허용하고 Robot 로그에 실패 원인을 남긴다.
- Backend DTO·검증·저장, DB 제약(V42), 통계·보고서 유형, Frontend 라벨과 JSON/OpenAPI 계약까지 로컬 반영·검증했다.

### 배포·통합 확인

1. V42가 운영 DB에 적용된 뒤 Backend가 기동되는지 확인한다.
2. Robot이 선택 필드의 생략 또는 명시적 `null` 중 한 형식을 일관되게 사용하는지 확인한다.
3. 구버전 Backend는 `MISSING`을 거부하므로 Robot과 Backend 배포 버전을 함께 맞춘다.
4. 운영 환경에서 이미지 업로드 → Incident 수신 → DB 연결 → 서명 URL JPEG 조회를 실차로 확인한다.

### 완료 기준

- 미설치 시험 사진 1장으로 이미지 업로드, Incident 수신, DB 결함 생성, 관리자 이미지 조회가 끝까지 성공한다.

## 6. P1-3 — 파손 탐지 이미지 미촬영·미전송

- 상태: 1차 패치 적용, 실차 회귀 검증 전.
- 파손 판정 시 증거 이미지 생성 여부, 업로드 HTTP 응답, 반환 `image_id`, Incident 연결을 한 타임라인으로 확인한다.
- 파손 이미지가 500KB 제한을 넘을 때 압축·리사이즈 결과와 재시도 여부도 확인한다.

### 완료 기준

- 파손 시험 사진으로 `DAMAGED` Incident와 Volume JPEG가 연결되고 관리자 화면에서 육안 확인된다.
- 업로드 실패 시 `image_id=null`과 실패 원인이 로그에 남아 무응답처럼 보이지 않는다.

## 7. P1-4 — 통신 통합 후 주행·주차·장애물 우회 회귀

### 현상

- 통신 코드와 주행 코드를 합친 뒤 주행 및 주차가 정상 동작하지 않는다.
- 순찰 중 점자블록을 장애물로 판단해 정지하며, 우회가 필요한 상황에서 계속 멈춘다.

### 확인 항목

1. 통신 스레드·콜백이 주행 제어 주기와 모터 정지 조건에 영향을 주는지 확인한다.
2. `SAFE_STOP`, 일반 장애물 정지, 일시 장애물 대기, 우회 가능 장애물을 서로 구분한다.
3. 카메라의 점자블록 결함 판정과 주행 장애물 판정이 같은 신호를 잘못 공유하는지 확인한다.
4. START·PAUSE·RESUME·COMPLETE·CANCEL 직후 실제 모터·FSM 상태를 로그로 남긴다.
5. 주차 진입, 정렬, 최종 정지 각 단계의 Timeout과 센서 값을 기록한다.

### 완료 기준

- 통신 활성 상태에서도 시작, 순찰, 장애물 우회, 주차, 정상 완료가 한 번 이상 연속 성공한다.
- 점자블록 자체를 장애물로 오인해 영구 정지하지 않는다.
- 실제 안전 장애물에서는 즉시 정지하고, 해제·우회 정책에 따라 예측 가능한 상태로 복구한다.

## 8. 실행 순서

1. P0-1 좀비 Mission 재현과 상태 타임라인 확보
2. P0-1 취소·완료·재할당 복구 및 회귀 테스트
3. P0-2 계획시간 자동 종료 검증
4. P1-1 이미지 송수신 로그 정합
5. P1-3 파손 이미지 패치 회귀 검증
6. P1-2 미설치 결함 운영 배포·실차 E2E
7. P1-4 주행·주차·장애물 우회 회귀 검증

P0 두 항목이 완료되기 전에는 새 기능보다 Mission 종료 정합성과 Robot 재할당 가능 상태를 우선한다.

## 9. 관련 정본과 최근 작업

- `web/docs/time-based-mission-contract.md`
- `web/docs/final-mqtt-topic-schema-v2.md`
- `CHANGLOGS/2026-08-04_1130_mission-communication-integration.md`
- `CHANGLOGS/2026-08-04_1154_robot-runtime-event-wiring.md`
- `CHANGLOGS/2026-08-02_1430_emergency-stop-mission-resume.md`
