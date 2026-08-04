# 길봄 MQTT Topic · 임베디드 · DB 매핑 정의서

> **DEPRECATED / SUPERSEDED:** 이 문서는 `tbs/v1` 기준의 과거 매핑 보관본이다.
> 현행 MQTT 계약은 [`final-mqtt-topic-schema-v2.md`](./final-mqtt-topic-schema-v2.md)와
> [`mqtt-v2-contract-freeze.md`](./mqtt-v2-contract-freeze.md)를 따른다.

> 문서 상태: 구현 기준 초안  
> 작성일: 2026-07-26  
> 담당 경계: 팀원 B의 MQTT·로봇·텔레메트리·임무·명령을 중심으로 작성하며, 탐지 사건의 업무 처리는 팀원 C가 담당한다.

---

## 1. 문서 목적

이 문서는 다음 네 가지 기준을 한곳에 연결한다.

1. 로봇과 서버가 사용할 최종 MQTT v1.0 Topic과 Payload
2. Jetson Orin Nano와 현재 센서 구성에서 실제 생성할 수 있는 값
3. MQTT 필드를 PostgreSQL 최종 DDL의 테이블·칼럼에 저장하는 방법
4. 현재 Flyway, DB Role 분리, TLS, MQTT 계정·ACL 정책을 적용하는 방법

이 문서에서 말하는 **최종 Topic 계약**은 다음 Namespace를 사용한다.

```text
tbs/v1/robots/{robot_id}/...
```

현재 소스의 `robots/{robotId}/...` Topic은 연결 검증을 위한 PoC 형식이다. 구현을 진행할 때에는 Spring 설정, Mosquitto ACL, Mock Robot, 테스트 코드를 이 문서의 최종 Topic으로 함께 변경해야 한다.

---

## 2. 참조 문서와 우선순위

| 우선순위 | 참조 파일 | 이 문서에서 사용하는 기준 |
|---:|---|---|
| 1 | `docs/길봄_MQTT 통신_SWAGGER REST SSE_API 명세서.pdf` | 최종 Topic, Payload, QoS, Retain, 명령 TTL, 보안 계약 |
| 2 | `docs/길봄_DB_DDL.sql` | 로봇·텔레메트리·명령·결함 관련 최종 테이블과 FK 기준 |
| 3 | `docs/길봄_기획의도_임베디드_상세_명세서.pdf` | 실제 하드웨어, 센서, MVP 구현 가능 범위 판단 |
| 4 | `web/backend/src/main/resources/db/migration/V1~V3` | 현재 Flyway가 실제로 생성하는 DB 범위 |
| 5 | `web/infra/postgres`, `web/infra/mosquitto` | DB Role 분리, 검증 SQL, TLS, 계정, ACL 운영 정책 |

> 충돌 시에는 최종 MQTT v1.0 계약을 따른다. 임베디드 상세 명세서 6.3의 `report/{event_id}/meta`, `robot/{id}/telemetry`, 전체 QoS 1, `image_url` 규칙은 이전 계약이다. 최종 계약의 `tbs/v1/...`, telemetry QoS 0, `image_id` 규칙으로 대체한다.

> `web/docs/mqtt-contract-v1.0.pdf`는 현재 0바이트 파일이다. 원본 계약을 확인할 때에는 `docs/길봄_MQTT 통신_SWAGGER REST SSE_API 명세서.pdf`를 사용한다.

---

## 3. 전체 데이터 흐름

```text
Jetson·센서
  ├─ MQTT QoS 0 ─ telemetry ───────────────┐
  ├─ MQTT QoS 1 ─ presence/state/events ───┤
  └─ HTTPS ─ incident image upload ────────┤
                                            ▼
                                  EC2 Mosquitto / Nginx
                                            ▼
                                      Spring Boot
                         ┌──────────────────┼──────────────────┐
                         ▼                  ▼                  ▼
                  PostgreSQL 이력     최신 상태 UPSERT      SSE 관제
                         │
                         ├─ robot·telemetry: 팀원 B
                         └─ incident→defect·storage: 팀원 C

관리자 웹 ─ HTTPS REST ─ Spring Boot ─ DB 명령 생성 ─ MQTT commands/request ─ 로봇
로봇 ─ MQTT commands/result ─ Spring Boot ─ 명령 결과 DB·감사 로그 ─ SSE ─ 관리자 웹
```

관리자 웹은 MQTT Broker에 직접 접속하지 않는다. 관리자 인증·인가·CSRF 검사와 명령 영속화는 Spring Boot에서 수행하고, 실시간 화면 갱신은 SSE로 중계한다.

---

## 4. Wire ID와 DB ID 변환 규칙

MQTT에 보이는 식별자와 DB PK를 동일한 값으로 취급하면 안 된다.

공통 Payload 형식은 다음으로 고정한다.

- UTF-8 JSON Object
- Key는 `snake_case`
- 시각은 ISO 8601 UTC `Z`
- `event_id`, `message_id`, `boot_id`, `command_id`, `result_id`, `image_id`는 UUIDv4
- 모든 Payload에 `schema_version: "1.0"` 포함
- 선택값을 만들 수 없으면 임의 값이나 빈 문자열 대신 `null` 또는 해당 `valid=false` 사용
- Topic 경로, 인증 username, Payload의 Robot 식별자는 서로 교차 검증

| MQTT·외부 값 | 예시 | 조회할 DB 칼럼 | 내부에서 사용할 FK |
|---|---|---|---|
| `mqtt_id` | `RBT-001` | `robot.robot.mqtt_id` | `robot.robot.robot_id` UUID |
| `mission_id` | `MIS-20260723-001` | `robot.mission.mission_code` | `robot.mission.mission_id` UUID |
| `requested_by.actor_id` | `ADM-0042` | `auth.admin_user.user_code` | `auth.admin_user.user_id` UUID |
| `event_id` | UUID | 그대로 사용 | `telemetry.robot_event.event_id` UUID |
| `message_id` | UUID | 그대로 사용 | `telemetry.robot_telemetry.message_id` UUID |
| `command_id` | UUID | 그대로 사용 | `robot.robot_command.command_id` UUID |
| `result_id` | UUID | 그대로 사용 | `robot.robot_command_result.result_id` UUID |
| `image_id` | UUID | 그대로 사용 | `storage.robot_image.image_id` 및 `storage.file_object.file_id` |
| receipt의 `incident_id` | `INC-20260723-000031` | `defect.defect.incident_code` | `defect.defect.defect_id` UUID |

적용 규칙은 다음과 같다.

- Topic 경로의 `{robot_id}`와 Payload의 `robot_id`가 같아야 한다.
- MQTT 인증 username도 최종적으로 `robot_id`와 같게 발급한다.
- `mission_id`라는 Wire 필드는 계약상 사람이 읽을 수 있는 `MIS-...` 코드이다. DB 저장 전에 `mission_code`로 조회하여 내부 UUID로 바꾼다.
- 존재하지 않거나 다른 로봇에 배정된 `mission_id`는 저장·실행하지 않고 거부한다.
- UUID, 코드, 시각은 로봇이 보낸 문자열을 임의 보정하지 않고 계약 검증 후 처리한다.

---

## 5. 최종 Topic 목록

| # | Topic | 방향 | QoS | Retain | 실제 기기 제공 | 주 DB |
|---:|---|---|---:|:---:|---|---|
| 1 | `tbs/v1/robots/{robot_id}/presence` | 로봇 → 서버 | 1 | O | 즉시 가능 | `robot_boot_session`, `robot_current_state`, `robot` |
| 2 | `tbs/v1/robots/{robot_id}/state` | 로봇 → 서버 | 1 | O | MVP 가능, 일부 health는 제한 | `robot_current_state` |
| 3 | `tbs/v1/robots/{robot_id}/telemetry` | 로봇 → 서버 | 0 | X | 센서 장착 여부에 따라 조건부 | `robot_telemetry`, `robot_current_state` |
| 4 | `tbs/v1/robots/{robot_id}/events/incident` | 로봇 → 서버 | 1 | X | `DAMAGED`, `MISSING` 가능 | `robot_event`, `defect_observation`, `defect`, `robot_image` |
| 5 | `tbs/v1/robots/{robot_id}/events/battery` | 로봇 → 서버 | 1 | X | 잔량 회로 또는 추정 정책 확정 후 | `robot_event`, `robot_battery_event` |
| 6 | `tbs/v1/robots/{robot_id}/events/safety` | 로봇 → 서버 | 1 | X | 장애물·경계 소실 MVP 가능 | `robot_event`, `robot_safety_event` |
| 7 | `tbs/v1/robots/{robot_id}/events/mission` | 로봇 → 서버 | 1 | X | Mission 상태머신 구현 후 가능 | `robot_event`, `robot_mission_event`, `mission_status_history` |
| 8 | `tbs/v1/robots/{robot_id}/commands/request` | 서버 → 로봇 | 1 | X | 6개 MVP 명령 수신 | `robot_command`, `audit_log` |
| 9 | `tbs/v1/robots/{robot_id}/commands/result` | 로봇 → 서버 | 1 | X | 명령 Handler 구현 후 가능 | `robot_command_result`, `robot_command` |
| 10 | `tbs/v1/robots/{robot_id}/ingest/receipt` | 서버 → 로봇 | 1 | X | 심화 | `mqtt_ingest_log`, `defect` |

백엔드 구독 필터는 다음과 같다.

```text
tbs/v1/robots/+/presence
tbs/v1/robots/+/state
tbs/v1/robots/+/telemetry
tbs/v1/robots/+/events/#
tbs/v1/robots/+/commands/result
```

로봇 구독 필터는 다음과 같다.

```text
tbs/v1/robots/{자기 robot_id}/commands/request
tbs/v1/robots/{자기 robot_id}/ingest/receipt    # 심화
```

---

## 6. 임베디드에서 실제 생성 가능한 정보

### 6.1 하드웨어 기준

| 장치·구성 | 제공 가능한 값 | 제한 |
|---|---|---|
| Jetson Orin Nano | 부팅 ID, seq, 로봇 모드, 소프트웨어 health, Jetson 최고 열 존 온도 | `tj-thermal` 읽기 구현 필요 |
| YDLidar X2 | 장애물 거리, 장애물 정지·해제, 경계 소실, LiDAR health | 5~10도 하향 설치와 통제 코스 기준 |
| 하향 카메라 | 점자블록 LINEAR/DOT, DAMAGED/MISSING 판정, confidence, bbox, 대표 JPEG | MISSING은 판정 점수와 예상 영역이 없으면 confidence·bbox가 null일 수 있음 |
| YOLO + TensorRT | 모델명·버전, confidence, block type, damage 판정 | 트랙 확정·쿨다운 로직 필요 |
| GPS NEO-M8N | 위도·경도, fix 유효 여부, accuracy 추정, course 기반 heading | GPS는 waypoint 항법이 아니라 사건 위치 태깅용이다. 장착 여부 우선 확인 |
| 온습도 센서 | 외기 온도·습도 | 실제 장착·드라이버 완료 시에만 발행 |
| 배터리 쉴드 | 전원 공급 | 잔량 측정 회로 존재 여부가 미확정이다. 없으면 시간 기반 추정 |
| MotorHat·DC 모터 | 명령 중인지에 따른 `moving` 추정 | 엔코더·IMU가 없어 실제 속도·주행 거리를 정확히 측정할 수 없음 |
| systemd·로컬 프로세스 | 정상 종료, 프로세스 재시작, 일부 component health | 물리 모터 고장을 확정하려면 피드백 센서가 추가로 필요 |

### 6.2 필드별 제공 원칙

| 필드 | MVP 처리 |
|---|---|
| `position.valid` | GPS fix가 있을 때만 `true`. 더미 좌표는 반드시 `false` |
| `position.lat/lon` | fix가 없으면 `null` 권장. 임의 좌표를 실제 위치처럼 보내지 않음 |
| `position.accuracy_m` | HDOP 환산을 구현할 수 있을 때만 값 제공 |
| `heading_deg` | GPS course를 신뢰할 수 있을 때만 제공, 아니면 `null` |
| `motion.moving` | 모터 제어 상태로 추정 가능 |
| `motion.speed_mps` | 엔코더가 없으므로 `null` 또는 명확한 추정값 |
| `battery.percent` | 잔량 회로 확인 전에는 측정값으로 위장 금지 |
| `battery.source` | 회로 실측값은 `MEASURED`, 가동시간 추정은 `ESTIMATED` |
| `battery.valid` | 값이 오래됐거나 센서 오류이면 `false` |
| `system.jetson_temp_c` | Jetson 전체 thermal zone 중 최고값 |
| `environment.*` | 온습도 센서 장착 시 선택 제공 |
| `health.motor` | 피드백이 없으면 `UNKNOWN`, 드라이버 통신 실패 등 확인 가능한 오류만 `FAULT` |
| `distance_m` | GPS 누적 추정 또는 `null`; 오도메트리 실측값으로 표시하지 않음 |

---

## 7. Topic별 Payload와 DB 매핑

### 7.1 Presence

#### Topic 설정

| 항목 | 값 |
|---|---|
| Topic | `tbs/v1/robots/{robot_id}/presence` |
| 방향 | 로봇 → 서버 |
| QoS | 1 |
| Retain | O |
| 발행 시점 | MQTT 연결 직후, 정상 종료 직전, 비정상 단절 LWT |
| 역할 | 로봇의 온라인·오프라인과 부팅 세션 식별 |

#### 최종 Payload Schema

| 필드 | JSON 타입 | ONLINE | OFFLINE | 허용값·규칙 |
|---|---|:---:|:---:|---|
| `schema_version` | string | 필수 | 필수 | 현재 `1.0` |
| `robot_id` | string | 필수 | 필수 | Topic의 `{robot_id}` 및 MQTT username과 같아야 함 |
| `boot_id` | string(UUID) | 필수 | 필수 | 로봇 프로세스가 시작될 때마다 새 UUIDv4 생성 |
| `status` | string | 필수 | 필수 | `ONLINE`, `OFFLINE` |
| `connected_at` | string(date-time) | 필수 | 사용 안 함 | ISO 8601 UTC `Z` |
| `reason` | string | 사용 안 함 | 필수 | `GRACEFUL_SHUTDOWN`, `CRITICAL_BATTERY_SHUTDOWN`, `SYSTEM_FAULT_SHUTDOWN`, `UNGRACEFUL_DISCONNECT` |

ONLINE과 OFFLINE은 같은 `PresencePayload` 객체로 수신하고 `status`에 따라 조건부 검증한다. 사용하지 않는 필드는 빈 문자열이나 `null`을 보내지 않고 JSON에서 생략한다.

#### ONLINE 최종 예시

```json
{
  "schema_version": "1.0",
  "robot_id": "RBT-001",
  "boot_id": "572b2e59-d31f-445f-a43d-7df80ba64203",
  "status": "ONLINE",
  "connected_at": "2026-07-26T05:00:00.000Z"
}
```

로봇이 MQTT 연결을 완료한 직후 직접 발행한다.

#### OFFLINE 최종 예시 — 정상 종료

```json
{
  "schema_version": "1.0",
  "robot_id": "RBT-001",
  "boot_id": "572b2e59-d31f-445f-a43d-7df80ba64203",
  "status": "OFFLINE",
  "reason": "GRACEFUL_SHUTDOWN"
}
```

로봇이 systemd stop 등 정상 종료를 수행할 때 직접 발행한 뒤 MQTT 연결을 종료한다.

#### OFFLINE 최종 예시 — LWT

```json
{
  "schema_version": "1.0",
  "robot_id": "RBT-001",
  "boot_id": "572b2e59-d31f-445f-a43d-7df80ba64203",
  "status": "OFFLINE",
  "reason": "UNGRACEFUL_DISCONNECT"
}
```

로봇이 MQTT 연결 시 Broker에 LWT로 등록한다. 전원 차단, LTE 단절, 프로세스 비정상 종료가 발생하면 Broker가 대신 발행한다.

#### DB 저장 매핑

| 입력값 | `telemetry.robot_boot_session` | `telemetry.robot_current_state` | 처리 |
|---|---|---|---|
| `mqtt_id` | 코드 조회 후 `robot_id` UUID | `robot_id` UUID | `robot.robot.mqtt_id`로 조회 |
| `boot_id` | PK `boot_id` | `boot_id` | ONLINE과 OFFLINE의 부팅 세션 일치 검증 |
| `schema_version` | `schema_version` | - | 지원 버전 검증 |
| `status` | `presence_status` | `presence_status` | 현재 온라인 상태 갱신 |
| `connected_at` | `connected_at` | - | ONLINE에서만 저장 |
| `reason` | `disconnect_reason` | `presence_reason` | OFFLINE에서만 저장 |
| 서버 수신 시각 | `last_presence_received_at` | `presence_received_at` | 서버가 생성 |
| OFFLINE 서버 수신 시각 | `disconnected_at` | - | LWT에는 실제 종료 시각이 없으므로 서버 수신 시각 저장 |
| 정상 종료 여부 | `graceful_shutdown` | - | 로봇이 직접 OFFLINE을 발행했으면 true, Broker LWT이면 false |

ONLINE 여부는 Telemetry 도착 여부만으로 판단하지 않고 Presence를 우선 사용한다. 마지막 Retained State가 `PATROLLING`이어도 Presence가 `OFFLINE`이면 관제 화면에는 오프라인으로 표시한다.

배터리가 LOW이거나 상태 이상으로 정지했더라도 MQTT 연결이 유지되면 Presence는 `ONLINE`이다. 실제로 치명적 배터리 부족이나 복구 불가능한 시스템 오류 때문에 로봇이 종료될 때만 `CRITICAL_BATTERY_SHUTDOWN` 또는 `SYSTEM_FAULT_SHUTDOWN`으로 OFFLINE을 직접 발행한다. 통신 두절은 Broker LWT의 `UNGRACEFUL_DISCONNECT`로 처리한다.

### 7.2 State

```text
tbs/v1/robots/{robot_id}/state
QoS 1 / Retain O
상태 전이 시 + 30초 주기
```

| Payload | DB 매핑 |
|---|---|
| `boot_id` | `robot_current_state.boot_id` |
| `seq` | `robot_current_state.state_seq` |
| `updated_at` | `state_updated_at` |
| 서버 수신 시각 | `state_received_at` |
| `mode` | `mode` |
| `reason_code` | `reason_code` |
| `mission.mission_id` | `mission_code` 조회 후 `mission_id` UUID |
| `mission.state` | `mission_state` |
| `battery.valid` | `battery_valid` |
| `battery.percent` | `battery_percent` |
| `battery.state` | `battery_state` |
| `health.camera/lidar/gps/battery/motor/storage` | 각 `health_*` 칼럼 |
| 전체 JSON | `state_raw_payload` |

`overall_health_status`는 로봇 Payload를 그대로 복사하는 필드가 아니라 서버 정책으로 `NORMAL`, `WARNING`, `CRITICAL`을 계산하는 값이다.

MVP mode는 `BOOTING`, `READY`, `PATROLLING`, `PAUSED`, `SAFE_STOP`, `ERROR`, `SHUTTING_DOWN`이다. `RETURNING_TO_CHARGE`, `CHARGING`은 2차 예약값이다. `OFFLINE`은 mode가 아니라 Presence 상태다.

### 7.3 Telemetry

```text
tbs/v1/robots/{robot_id}/telemetry
QoS 0 / Retain X / 5초 주기
```

| Payload | 필수 | `telemetry.robot_telemetry` | `telemetry.robot_current_state` |
|---|:---:|---|---|
| `message_id` | O | PK `message_id` | `last_telemetry_message_id` |
| `schema_version` | O | `schema_version` | - |
| `robot_id` | O | 코드 조회 후 `robot_id` UUID | PK `robot_id` |
| `boot_id` | O | `boot_id` | `boot_id` |
| `seq` | O | `seq` | 최신 판정 보조 |
| `sampled_at` | O | `sampled_at` | `telemetry_sampled_at` |
| 서버 수신 시각 | 서버 | `received_at` | `telemetry_received_at` |
| `mission_id` | 조건부 | 코드 조회 후 UUID | `mission_id` |
| `position.valid` | O | `position_valid` | `position_valid` |
| `position.lat/lon` | 조건부 | `location` Point | `location` Point |
| `position.accuracy_m` | X | `location_accuracy_m` | 동일 |
| `position.heading_deg` | X | `heading_deg` | 동일 |
| `motion.moving` | X | `motion_moving` | 동일 |
| `motion.speed_mps` | X | `speed_mps` | 동일 |
| `battery.valid` | O | `battery_valid` | 동일 |
| `battery.percent` | 조건부 | `battery_percent` | 동일 |
| `battery.state` | O | `battery_state` | 동일 |
| `battery.source` | X | `battery_source` | 동일 |
| 배터리 확장값 | X | `voltage_v`, `current_a`, `charging`, `battery_temperature_c`, `estimated_runtime_sec` | 동일 |
| `system.jetson_temp_c` | O | `jetson_temperature_c` | 동일 |
| `environment.temperature_c` | X | `environment_temperature_c` | 동일 |
| `environment.humidity_pct` | X | `environment_humidity_pct` | 동일 |
| `mission.state` | X | `mission_state` | `mission_state` |
| `mission.elapsed_sec` | X | `mission_elapsed_sec` | 동일 |
| `mission.incident_count` | X | `mission_incident_count` | 동일 |
| 전체 JSON | O | `raw_payload` | 저장하지 않음 |

PostGIS Point는 `ST_SetSRID(ST_MakePoint(lon, lat), 4326)`처럼 **경도, 위도 순서**로 생성한다.

저장 원칙은 다음과 같다.

1. `message_id` PK와 `(robot_id, boot_id, seq)` UNIQUE로 중복을 방어한다.
2. 이력 INSERT와 최신 상태 UPSERT를 한 트랜잭션에서 처리한다.
3. `received_at`이 아니라 `sampled_at`이 더 최신인 경우에만 Current State를 갱신한다.
4. `position.valid=false`인 표본은 마지막 유효 위치를 지우거나 덮어쓰지 않는다.
5. QoS 0 Telemetry는 통신 단절 중 저장·재전송하지 않는다. 재연결 시 최신 표본만 발행한다.

### 7.4 Incident Event

```text
tbs/v1/robots/{robot_id}/events/incident
QoS 1 / Retain X
event_type = BRAILLE_BLOCK_INCIDENT_DETECTED
```

저장 흐름은 다음과 같다.

```text
events/incident
  ├─ 공통 Envelope → telemetry.robot_event
  ├─ image_id 확인 → storage.robot_image / storage.file_object
  ├─ 공간·유형 기준 결함 생성 또는 병합 → defect.defect
  ├─ 불변 관측 저장 → defect.defect_observation
  └─ 수신·중복·병합 결과 → integration.mqtt_ingest_log
```

| Payload | DB 매핑 |
|---|---|
| 공통 `event_id`, `event_type`, `boot_id`, `seq`, 시각 | `telemetry.robot_event` |
| `source_component=ai` | `robot_event.source_component` |
| `location.valid` | `defect_observation.location_valid` |
| `location.lat/lon` | `defect_observation.location` |
| `location.accuracy_m` | `defect_observation.location_accuracy_m` |
| `location.zone_id` | `defect_observation.zone_id`, 결함 생성 시 `defect.zone_id` |
| `incident.asset_type` | `defect_observation.asset_type` |
| `incident.block_type` | `defect_observation.block_type` |
| `incident.defect_type` | `detected_defect_type` |
| `incident.confidence` | `confidence` |
| `incident.attributes.damage_ratio` | `damage_ratio` |
| 기타 `incident.attributes` | `attributes` JSONB |
| `evidence.image_id` | `defect_observation.image_id` |
| `evidence.captured_at` | `evidence_captured_at` |
| 이미지 크기 | `image_width`, `image_height` |
| `evidence.bbox_norm` | `bbox_norm` JSONB |
| `model.name/version` | `model_name`, `model_version` |

Robot 실제 발행 유형은 `DAMAGED`, `MISSING`이다. `INSTALLATION_INVALID`,
`UNKNOWN_ANOMALY`는 DB와 관리자 수정에만 사용한다. `MISSING`도 JPEG를 먼저
업로드하고 반환받은 `image_id`를 Incident에 넣어 관측과 연결한다.

이 Topic에서 B와 C의 경계는 다음과 같다.

| 처리 | 담당 |
|---|---|
| Broker, Topic, ACL, JSON 공통 Envelope 검증 | B |
| `telemetry.robot_event` 공통 수신·멱등 처리 | B |
| Incident 전용 필드 검증, 결함 생성·공간 병합 | C |
| `defect.defect`, `defect_observation`, 이미지 연결 | C |
| 수신 후 SSE 전달 경로 | B 지원, 결함 Payload 내용은 C |

정상 블록은 Incident로 발행하지 않는다. 정상 탐지 수는 로컬에서 집계하여 Mission 완료 사건의 `normal_detection_count`로만 전송한다.

이미지는 Incident보다 먼저 `POST /api/v1/images`로 업로드한다. MQTT에는 Base64, JPEG, URL, S3 object key를 넣지 않고 `image_id`만 넣는다. 10초 timeout과 1·2·4초 백오프로 3회 실패하면 `image_id=null`로 사건을 먼저 발행한다.

### 7.5 Battery Event

```text
tbs/v1/robots/{robot_id}/events/battery
QoS 1 / Retain X
상태 전이 시 1회 발행
```

| Payload | DB 매핑 |
|---|---|
| 공통 Envelope | `telemetry.robot_event` |
| `previous_state` | `robot_battery_event.previous_state` |
| `battery.valid` | `battery_valid` |
| `battery.percent` | `battery_percent` |
| `battery.state` | `battery_state` |
| `threshold_pct` | `threshold_pct` |
| `action_taken.mission_state` | `action_mission_state` |
| `action_taken.robot_mode` | `action_robot_mode` |

MVP event type은 `BATTERY_LOW`, `BATTERY_CRITICAL`, `BATTERY_RECOVERED`, `BATTERY_SENSOR_FAULT`, `BATTERY_SENSOR_RECOVERED`이다.

- LOW: 20% 이하, 임무 일시중단과 안전 정지
- CRITICAL: 10% 이하 예정, 실측 후 확정
- RECOVERED: 25% 이상 예정, 자동 재개하지 않고 관리자 명령 대기
- 잔량 회로가 없으면 시간 기반 추정 정책을 먼저 합의하고 `source=ESTIMATED`로 표시

### 7.6 Safety Event

```text
tbs/v1/robots/{robot_id}/events/safety
QoS 1 / Retain X
안전 상태 전이 시 1회 발행
```

| Payload | DB 매핑 |
|---|---|
| 공통 Envelope | `telemetry.robot_event` |
| `reason_code` | `robot_safety_event.reason_code` |
| `measurement.distance_m` | `distance_m` |
| `transition.mode_before` | `mode_before` |
| `transition.mode_after` | `mode_after` |
| `auto_resume_allowed` | `auto_resume_allowed` |

MVP에서 신뢰할 수 있는 사건은 장애물 정지·해제, 인도 경계 소실, LiDAR 오류, 내부 프로세스 오류다. 모터 피드백이 없으므로 `MOTOR_FAULT`는 확인 가능한 드라이버 통신 오류 등으로 범위를 제한하고, 알 수 없으면 health를 `UNKNOWN`으로 둔다.

> 0.5초 안전 정지는 MQTT가 수행하지 않는다. 로봇 내부 LiDAR 안전 프로세스가 모터를 직접 정지하고, Safety Topic은 정지 후 서버에 사실을 보고하는 채널이다.

### 7.7 Mission Event

```text
tbs/v1/robots/{robot_id}/events/mission
QoS 1 / Retain X
```

| Payload | DB 매핑 |
|---|---|
| 공통 Envelope | `telemetry.robot_event` |
| `event_type` | Mission 상태 전이 판단 |
| `result.duration_sec` | `robot_mission_event.duration_sec` |
| `result.distance_m` | `distance_m` |
| `result.incident_count` | `incident_count` |
| `result.normal_detection_count` | `normal_detection_count` |
| `result.completion_reason` | `completion_reason` |
| 상태 전이 | `robot.mission.mission_status` 및 `mission_status_history` |
| 이동 좌표 누적 | `robot.mission_trajectory` |

event type은 `MISSION_STARTED`, `MISSION_PAUSED`, `MISSION_RESUMED`, `MISSION_COMPLETED`, `MISSION_CANCELLED`, `MISSION_FAILED`이다.

로봇 MVP의 Mission은 waypoint 항법이 아니라 얇은 순찰 세션이다. 서버가 `START_MISSION`으로 `mission_id`와 `zone_id`를 전달하면 로봇이 세션을 시작하고, 정지·재개·취소·완료 상태와 집계를 다시 보고한다. Polygon과 경로 GeoJSON은 MQTT로 보내지 않는다.

### 7.8 Commands Request

```text
tbs/v1/robots/{robot_id}/commands/request
QoS 1 / Retain 절대 금지
```

| Payload | DB 원천 |
|---|---|
| `schema_version` | `robot_command.schema_version` |
| `command_id` | `robot_command.command_id` |
| `command_type` | `robot_command.command_type` |
| `issued_at` | `issued_at` |
| `expires_at` | `expires_at` |
| `requested_by.actor_type` | `requested_by_actor_type` |
| `requested_by.actor_id` | `requested_by_actor_code` 또는 사용자 `user_code` |
| `parameters` | `parameters` JSONB |
| MQTT 5 Message Expiry | `message_expiry_sec` |

| 명령 | MVP | 권장 TTL | 기기 처리 |
|---|:---:|---:|---|
| `START_MISSION` | O | 60초 | `mission_id`, `zone_id` 저장 후 순찰 세션 시작 |
| `PAUSE_MISSION` | O | 30초 | 안전 정지 후 일시중지 |
| `RESUME_MISSION` | O | 30초 | 안전 조건 확인 후 재개 |
| `COMPLETE_MISSION` | O | 30초 | 현재 임무를 정상 완료 |
| `CANCEL_MISSION` | O | 30초 | `mission_id`, `reason`으로 대상 임무를 확정하고 `MISSION_CANCELLED` 보고 후 로컬 연결 해제 |
| `EMERGENCY_STOP` | O | 10초 | 즉시 구동 정지 |
| `CLEAR_EMERGENCY_STOP` | O | 30초 | 안전 조건 확인 후 해제 |
| `RETURN_TO_CHARGE` | 2차 | - | 충전소 복귀 |
| `UPDATE_CONFIG` | 2차 | - | 설정 변경 |

백엔드는 REST 요청을 받은 뒤 다음 순서로 처리한다.

1. 관리자 세션, RBAC, 로봇 배정 범위, CSRF, 중요 명령 확인을 검증한다.
2. Idempotency Key와 `command_id`로 중복 생성을 막는다.
3. `robot_command`를 `CREATED`로 저장하고 감사 로그를 남긴다.
4. MQTT Publish 성공 시 `PUBLISHED`로 바꾼다.
5. `expires_at`과 같은 초를 MQTT 5 Message Expiry로 설정한다.
6. 결과가 TTL 안에 오지 않으면 `EXPIRED`로 처리한다.

로봇은 최근 `command_id` 최소 50개와 이전 결과를 보관한다. 중복 수신 시 명령을 재실행하지 않고 이전 결과만 다시 발행한다.

### 7.9 Commands Result

```text
tbs/v1/robots/{robot_id}/commands/result
QoS 1 / Retain X
```

| Payload | DB 매핑 |
|---|---|
| `result_id` | `robot_command_result.result_id` |
| `command_id` | `command_id` FK |
| `robot_id` | 코드 조회 후 `robot_id` UUID |
| `status` | `result_status` |
| `received_at` | 로봇이 명령을 받은 시각 |
| `completed_at` | 실행 완료 시각 |
| 서버 수신 시각 | `server_received_at` |
| `reason_code` | `reason_code` |
| `state.mode` | `state_mode` |
| `state.reason_code` | `state_reason_code` |
| 전체 JSON | `raw_payload` |

결과 status는 `ACCEPTED`, `COMPLETED`, `FAILED`, `REJECTED`, `EXPIRED`이다. 즉시 명령은 일반적으로 최종 결과 한 번만 발행한다. 오래 걸리는 2차 명령만 `ACCEPTED` 후 최종 결과를 발행한다.

결과 저장 후 `robot_command.command_status`를 같은 트랜잭션에서 갱신하고 `robot.command.updated` SSE를 보낸다.

### 7.10 Ingest Receipt

```text
tbs/v1/robots/{robot_id}/ingest/receipt
QoS 1 / Retain X / 심화
```

| Payload | DB 원천 |
|---|---|
| `event_id` | `telemetry.robot_event.event_id` |
| `ingest_status` | `integration.mqtt_ingest_log.processing_status` |
| `incident_id` | `defect.defect.incident_code` |
| `received_at` | 서버 저장 완료 시각 |

`STORED`, `MERGED`, `DUPLICATE`, `REJECTED`를 사용한다. MVP에서는 PUBACK 시점에 로봇 Outbox를 삭제하므로 Receipt를 구현하지 않아도 된다.

---

## 8. 공통 Event Envelope와 멱등성

Incident, Battery, Safety, Mission은 다음 공통 필드를 가진다.

| 공통 필드 | `telemetry.robot_event` | 규칙 |
|---|---|---|
| `schema_version` | `schema_version` | 현재 `1.0` |
| `event_id` | PK `event_id` | QoS 1 기술 중복 제거 키 |
| `event_type` | `event_type` | Topic별 enum 검증 |
| `robot_id` | 코드 조회 후 `robot_id` | Topic 경로와 일치 |
| `boot_id` | `boot_id` | 존재하는 Boot Session과 연결 |
| `seq` | `seq` | 부팅 내 모든 Topic 공용 증가 순번 |
| `source_component` | `source_component` | `ai`, `power`, `safety`, `mission` |
| `occurred_at` | `occurred_at` | 실제 사건 시각, 재전송 시 유지 |
| `sent_at` | `sent_at` | 재전송 시 갱신 가능 |
| `ttl_sec` | `raw_payload` | 60~300초, `occurred_at` 기준 DB 처리 전 만료 검증 |
| 서버 수신 시각 | `received_at`, `last_received_at` | 서버가 생성 |
| `mission_id` | 코드 조회 후 `mission_id` | 임무가 없으면 `null` |
| 전체 JSON | `raw_payload` | 민감정보 제외 |

`event_id`가 같으면 MQTT 재전송에 의한 기술 중복이다. `(robot_id, boot_id, seq)`도 UNIQUE로 방어하되, 재부팅 후 seq가 다시 시작하므로 `(robot_id, seq)`만 UNIQUE로 사용하면 안 된다.

다른 `event_id`지만 위치와 결함 종류가 같으면 기술 중복이 아니라 팀원 C가 처리할 공간 병합 후보이다.

> PDF 추출 화면에서 `"seq "`처럼 공백이 보이는 부분은 문서 레이아웃 문제로 판단한다. 실제 JSON Key는 공백 없는 `seq`로 고정한다.

---

## 9. DB 테이블 책임과 데이터 원천

| Schema.Table | 담당 | MQTT·API 원천 | 용도 |
|---|---|---|---|
| `robot.robot` | B | 등록 API, Presence | 로봇 코드·MQTT Client·운영 상태 |
| `robot.robot_credential` | A+B | Credential 발급 | MQTT Password hash, API Key hash, 인증서 fingerprint |
| `auth.user_robot_assignment` | A+B | 관리자 배정 API | 사용자의 로봇 조회·제어 범위 |
| `robot.patrol_area` | B | REST | 순찰 구역 Polygon |
| `robot.patrol_route` | B | REST | 계획 경로 LineString |
| `robot.mission` | B | REST + Mission Event | 임무 현재 상태·진행률·집계 |
| `robot.robot_command` | B | 관리자 REST | 발행할 명령과 TTL |
| `telemetry.robot_boot_session` | B | Presence | 부팅·연결·종료 이력 |
| `telemetry.robot_telemetry` | B | Telemetry | 5초 측정 이력 |
| `telemetry.robot_current_state` | B | Presence + State + 최신 Telemetry | 대시보드 Read Model |
| `telemetry.robot_event` | B, Incident는 C와 공유 | Events 4종 | 공통 사건 Envelope와 원본 |
| `telemetry.robot_battery_event` | B | Battery Event | 배터리 전이 상세 |
| `telemetry.robot_safety_event` | B | Safety Event | 안전 정지 상세 |
| `telemetry.robot_mission_event` | B | Mission Event | 임무 결과 상세 |
| `robot.mission_status_history` | B | REST·Mission Event·시스템 | 임무 상태 불변 이력 |
| `robot.mission_trajectory` | B | 유효 Telemetry 위치 | 실제 이동 LineString |
| `robot.robot_command_result` | B | Commands Result | 명령 ACK·실행 결과 |
| `integration.mqtt_ingest_log` | B | MQTT Inbound·Outbound | 처리 상태·오류·Aggregate 연결 |
| `storage.file_object` | C/A | Image HTTPS | Private S3 object metadata |
| `storage.robot_image` | C | Image HTTPS + Incident | event와 image 연결 |
| `defect.defect` | C | Incident 처리 | 공간 병합된 관리 대상 |
| `defect.defect_observation` | C | Incident | 불변 AI 관측 사실 |
| `audit.audit_log` | A 공통, 각 도메인이 기록 | 명령·Credential·권한 변경 | 보안·업무 감사 |

최종 DDL에는 위 테이블과 FK·CHECK·INDEX가 정의되어 있지만, DDL 파일 자체에는 Runtime Role용 `GRANT`가 없다. 실제 Flyway Migration을 만들 때 각 Schema와 테이블에 필요한 최소 권한을 명시해야 한다.

---

## 10. Flyway 적용 상태와 B 파트 Migration 원칙

### 10.1 현재 실제 적용 대상

| Flyway | 현재 생성 범위 |
|---|---|
| V1 | `auth` 관리자·Role·TOTP·복구 코드 |
| V2 | `report` 보고서·불변 버전, `audit` Append-only 감사 로그 |
| V3 | `session_store` Spring Session·세션 보안 상태 |

현재 V1~V3에는 `robot`, `telemetry`, `integration`, `storage`, `defect` 업무 테이블이 없다. `docs/길봄_DB_DDL.sql`은 최종 참고 DDL일 뿐 자동 실행되지 않는다.

따라서 다음 상태를 구분해야 한다.

- **설계됨:** 최종 DDL에 B 테이블과 FK가 있음
- **아직 Migration 없음:** 현재 실행 DB에는 B 테이블이 생성되지 않음
- **금지:** 최종 DDL 전체를 수동 실행하여 V1~V3와 중복 생성

### 10.2 권장 Migration 분리

Flyway Version 번호는 A·B·C가 공용으로 사용하므로 Jira 또는 공용 문서에서 먼저 예약한다. 아래 이름의 `{N}`은 팀 합의 후 배정한다.

```text
V{N}__create_robot_core_schema.sql
V{N+1}__create_telemetry_and_command_schema.sql
V{N+2}__create_mqtt_ingest_schema.sql
V{C-assigned}__create_incident_storage_and_defect_schema.sql
```

권장 의존 순서는 다음과 같다.

1. 기존 V1의 `auth.admin_user` 생성
2. `robot.robot`, Credential, 사용자-로봇 배정
3. 순찰 구역·경로·Mission·Command
4. Boot Session, Telemetry, Current State, Event 4종
5. Mission History·Trajectory·Command Result
6. MQTT Ingest Log
7. C의 Image·Defect·Observation과 Incident FK

각 Migration에는 다음이 포함되어야 한다.

- Schema, Table, PK, FK, CHECK, UNIQUE, GIST/일반 Index
- 테이블·칼럼 Comment
- `"${applicationRole}"`에 대한 명시적 최소 권한
- Sequence를 사용하는 경우 필요한 `USAGE`, `SELECT`
- 멱등 키와 오래된 Current State 덮어쓰기 방지 검증 SQL
- Migration 적용 후 Runtime Role로 수행하는 Rollback 기반 Smoke Test

### 10.3 변경 규칙

- 적용된 `V*.sql`은 수정하지 않고 새 Version을 추가한다.
- Hibernate `ddl-auto=none`을 유지한다.
- Spring Session 자동 DDL은 `never`를 유지한다.
- `flyway.clean-disabled=true`를 유지한다.
- Flyway 이력은 `flyway_history.flyway_schema_history`에 둔다.
- 로컬 기존 `postgres_data` Volume에는 초기 Role 생성 Script가 다시 실행되지 않는다. Role 분리를 새로 적용할 때에는 데이터를 보존할지 초기화할지 먼저 결정해야 한다.

---

## 11. DB Role과 보안

`web/infra/postgres/init/20-create-application-roles.sh`는 빈 DB 최초 초기화 때 다음 세 Role을 분리한다.

| Role | 용도 | 백엔드 Runtime 전달 |
|---|---|:---:|
| `gilbom_db_admin` | 빈 DB Bootstrap | X |
| `gilbom_flyway` | Schema·Table·Function 생성, Flyway 이력 | Flyway에만 전달 |
| `gilbom_app` | Spring Runtime DML | O |

보안 규칙은 다음과 같다.

- Bootstrap, Flyway, Runtime Role은 서로 달라야 한다.
- Flyway와 Runtime Role은 `NOSUPERUSER`, `NOCREATEDB`, `NOCREATEROLE`이다.
- `PUBLIC`의 Database·Public Schema CREATE 권한을 회수한다.
- Runtime Role에는 Migration이 명시적으로 부여한 DML만 허용한다.
- `audit.audit_log`와 `report.report_version`은 UPDATE·DELETE를 권한과 Trigger로 모두 차단한다.
- MQTT Password, Robot API Key 원문을 DB에 저장하지 않고 `robot_credential.secret_hash`에 검증용 Hash만 저장한다.
- TLS 인증서는 원문 인증서 대신 `certificate_fingerprint`와 수명주기만 DB에서 관리한다.
- DB Password, MQTT Password, Client Private Key를 SQL, Git, Docker Image, 로그에 넣지 않는다.

로컬 `.env`는 개발 편의용이며 Git에 커밋하지 않는다. 운영 Secret은 `.env`나 Compose YAML이 아니라 Secrets Manager·KMS 또는 제한된 인증서 경로에서 주입한다.

---

## 12. MQTT TLS·계정·ACL 정의

### 12.1 MVP 보안 수준

MVP는 다음 조합을 사용한다.

```text
MQTT 5 + TLS 8883 + 기기별 username/password + Topic ACL
```

현재 Spring의 `now-mqtt-security-strategy=tls`는 서버 인증 TLS이다. 백엔드는 서버 인증서를 검증하지만 Client Certificate와 Private Key는 보내지 않는다. mTLS는 `mtls` 전략으로 전환하고 Broker에 Client CA와 `require_certificate true`를 적용할 때 활성화된다.

| 환경 | Broker 인증서 | Client가 신뢰하는 값 |
|---|---|---|
| 로컬 STS·Docker | 로컬 CA가 서명한 `local-broker-cert.pem` | `local-ca-cert.pem` |
| EC2 | `i15d101.p.ssafy.io`용 Public CA 인증서 | JVM·OS 기본 CA 또는 지정 Trust Store |
| mTLS 심화 | 위 서버 인증서 + 기기 Client 인증서 | Client Private Key는 기기별 Secret |

### 12.2 권장 최소 권한 ACL

로봇 username을 `RBT-001`처럼 `robot_id`와 같게 발급하면 `%u` 패턴을 사용할 수 있다.

```conf
# Robot: 자기 데이터만 발행하고 자기 명령만 구독
pattern write tbs/v1/robots/%u/presence
pattern write tbs/v1/robots/%u/state
pattern write tbs/v1/robots/%u/telemetry
pattern write tbs/v1/robots/%u/events/#
pattern write tbs/v1/robots/%u/commands/result
pattern read  tbs/v1/robots/%u/commands/request
pattern read  tbs/v1/robots/%u/ingest/receipt

# Backend service account
user gilbom
topic read  tbs/v1/robots/+/presence
topic read  tbs/v1/robots/+/state
topic read  tbs/v1/robots/+/telemetry
topic read  tbs/v1/robots/+/events/#
topic read  tbs/v1/robots/+/commands/result
topic write tbs/v1/robots/+/commands/request
topic write tbs/v1/robots/+/ingest/receipt
```

로봇에 `tbs/v1/robots/%u/#` 전체 Write를 허용하면 자기 명령 요청이나 Receipt까지 위조할 수 있으므로 Topic을 방향별로 나누어 허용한다.

현재 Mosquitto는 DB가 아니라 `passwd` 파일로 계정을 검증한다. 각 보안 요소의 역할은 다음과 같다.

| 요소 | 역할 |
|---|---|
| `robot.robot.mqtt_username` | 기기에 할당된 MQTT Principal 이름 |
| `robot.robot_credential` | Credential 종류·Hash·인증서 fingerprint·만료·폐기 상태를 관리하는 업무 Registry |
| Mosquitto `passwd` | Broker가 로그인 시 실제로 조회하는 Password Verifier |
| Mosquitto `acl` | 로그인한 Principal이 읽고 쓸 수 있는 Topic 제한 |

따라서 DB에 `robot_credential` 행을 추가하는 것만으로 Broker 계정이 생성되지는
않는다. 현재 Credential 서비스는 최초 발급 시 Broker 계정 생성 후 DB ACTIVE를
저장하고, 회전 시 DB의 활성 hash와 Broker hash가 같을 때 같은 username의
비밀번호를 교체한다. 기존 버전은 `REVOKED(ROTATED)`, 새 버전은 `ACTIVE`가 된다.
폐기는 예상 hash가 일치하는 ACTIVE 계정을 Broker에서 제거한 뒤 DB를 `REVOKED`로
전환한다. Robot 행 `FOR UPDATE` 잠금으로 버전을 직렬화하며, Broker 반영 후 DB나
필수 감사가 실패하면 최초 발급은 계정을 제거하고 회전·폐기는 이전 hash를 복구한다.
불일치는 `MQTT_CREDENTIAL_REGISTRY_DRIFT`로 실패 폐쇄한다.

이 보상은 요청 단위 정합성 통제다. 주기적 Drift 탐지·경보·자동 복구와 실제 TLS
Broker에서 이전 Secret 거부·새 Secret 허용·폐기 후 재연결 거부를 확인하는 E2E는
별도 운영 정합성 작업으로 추적한다. password-file 방식에서 이미 맺어진 연결의 즉시
강제 종료도 Broker 운영 통제 경로가 담당한다.

### 12.3 Replay와 명령 안전

- State·Telemetry·Event 4종은 한 Boot Session에서 하나의 `seq`를 공유한다.
- 새 Boot의 첫 State·Telemetry·Event는 `seq=1`이어야 하며, 이후 새 Claim은
  `last_seen_seq`보다 커야 한다. QoS 0 유실을 고려해 중간 순번 누락은 허용한다.
- Backend는 `telemetry.robot_current_state` 행을 `FOR UPDATE`로 잠근 뒤
  `robot_status_history`, `robot_telemetry`, `robot_event`의
  `(robot_id, boot_id, seq)`를 함께 조회한다. 별도 Replay Table은 추가하지 않는다.
- 같은 State JSON, 같은 Telemetry `message_id`·JSON, 같은 Event `event_id`의
  재수신만 멱등 처리한다. 같은 순번을 다른 Topic 또는 메시지가 사용하면
  `MQTT_SEQUENCE_CONFLICT`로 거부한다.
- 새 Boot 승인 시 이전 Boot를 `REPLACED_BY_NEW_BOOT`로 닫고 Current State의
  Boot별 측정값을 초기화한다. 종료·이전 Boot의 새 메시지나 새 Boot의
  `seq!=1`은 `MQTT_REPLAY_REJECTED`로 거부한다.
- Presence에는 `seq`가 없다. `UNGRACEFUL_DISCONNECT`·`HEARTBEAT_TIMEOUT`은 같은
  Boot의 ONLINE 재연결을 허용하지만, 교체된 Boot의 Retained ONLINE은 무시한다.
- Replay 판정과 Boot Sequence 갱신은 각 수신 서비스의 History·Mission·Defect
  업무 처리와 같은 Transaction에 포함한다. 실패하면 전체를 Rollback한다.
- Replay 거부 Metric은 `gilbom.mqtt.inbound.rejected`의 `topic`, `reason`
  제한 Tag만 사용하며 Payload·UUID·시각을 Tag에 넣지 않는다.
- Commands Request는 Retain을 절대 사용하지 않는다.
- `expires_at`과 MQTT 5 Message Expiry를 함께 적용한다.
- `command_id` 중복 수신 시 재실행하지 않는다.

### 12.4 수신 메시지 TTL과 미래 시각

| Topic | 기준시각 | 최대 허용 지연 | 미래 허용 오차 | 만료 처리 |
|---|---|---:|---:|---|
| Telemetry | `sampled_at` | 60초 | 5초 | History·Current State 처리 전 거부 |
| State | `updated_at` | 300초 | 5초 | Current State·History 처리 전 거부 |
| Event | `occurred_at` | Payload `ttl_sec` 60~300초 | 5초 | Event·Mission·Defect 처리 전 거부 |
| Command Result | 최종 `completed_at`, 그 외 `received_at` | 60초 | 5초 | Result 저장·Command 변경 전 거부 |

Command Result는 위 시간 검증과 별도로 원 Command의 `expires_at`을 넘으면
`MQTT_MESSAGE_EXPIRED`로 거부한다. 만료 또는 미래 시각 거부는
`gilbom.mqtt.inbound.rejected` Counter에 `topic`, `reason`의 제한된 Tag만 남기고,
Payload·식별자·시각 원문은 Metric Tag에 넣지 않는다.

Presence는 Retained ONLINE과 Broker LWT OFFLINE에 공통 기준시각이 없으므로 이
TTL 표에서 제외한다. 대신 Boot Session, 현재 연결 상태와 Replay 규칙으로 보호한다.
- 로봇과 백엔드 시간은 NTP로 동기화한다.
- MQTT Password와 API Key를 Payload, 로그, `raw_payload`, 감사 로그에 넣지 않는다.
- `integration.mqtt_ingest_log.raw_payload`에는 업무 JSON만 저장하고 인증 헤더·Credential은 저장하지 않는다.
- Broker의 8883만 외부에 노출하고 PostgreSQL, Backend 내부 포트, Actuator 상세 정보는 인터넷에 공개하지 않는다.

---

## 13. Outbox·중복·Current State 처리 규칙

### 13.1 로봇 Outbox

로컬 디스크 Outbox 대상은 Events 4종뿐이다.

```text
incident / battery / safety / mission
```

- SQLite 등에 `event_id`, Topic, Payload, 이미지 경로, 생성 시각, 전송 상태를 저장한다.
- QoS 1 PUBACK를 받은 뒤 삭제한다.
- 재연결 시 미전송 Event를 seq 순서로 다시 보낸다.
- `occurred_at`은 유지하고 `sent_at`만 갱신할 수 있다.
- Telemetry와 State는 Outbox에 저장하지 않는다.

### 13.2 서버 중복 처리

| 입력 | 중복 키 | 처리 |
|---|---|---|
| State | `(robot_id, boot_id, seq)`, JSON Payload | 완전히 같은 재수신은 멱등, 다른 Claim은 충돌 |
| Telemetry | `message_id`, `(robot_id, boot_id, seq)`, JSON Payload | 완전히 같은 재수신은 멱등, 다른 Claim은 충돌 |
| Event 4종 | `event_id`, `(robot_id, boot_id, seq)` | 같은 Event 재수신은 멱등, 다른 Claim은 충돌 |
| Command REST | Idempotency Key, `command_id` | 중복 명령 생성 방지 |
| Command Result | `result_id` | 중복 결과 저장 방지 |
| Image Upload | `event_id` | 재업로드 시 기존 `image_id` 반환 |

### 13.3 Transaction 경계

- Telemetry 이력 INSERT와 Current State UPSERT는 한 Transaction
- Event 공통 행과 세부 Event 행은 한 Transaction
- Command 생성과 중요 감사 로그는 한 Transaction
- Command Result 저장과 Command 상태 갱신은 한 Transaction
- Incident의 `robot_event`, 결함 생성·병합, Observation 저장, 이미지 연결은 B·C 서비스 경계를 정하되 최종적으로 원자성 또는 보상 정책을 명시

---

## 14. 현재 소스와 최종 계약의 차이

| 구분 | 현재 소스 | 최종 목표 |
|---|---|---|
| Namespace | `robots/{id}/...` | `tbs/v1/robots/{robot_id}/...` |
| Topic 수 | telemetry, status, alerts, commands, ack 중심 | Presence, State, Telemetry, Events 4종, Command 2종, Receipt |
| Payload 이름 | 테스트 예시에 camelCase 혼재 | `snake_case` 고정 |
| QoS | Spring 전역 `MQTT_QOS=1` | Telemetry 0, 나머지 계약 Topic 1 |
| Retain | Publisher 설정에 Topic별 정책 없음 | Presence·State만 O, Commands 절대 X |
| Subscription | telemetry + commands/ack | Presence + State + Telemetry + Events/# + Commands Result |
| ACL | 로컬·운영 모두 옛 `robots/...` | 최종 `tbs/v1/...` 최소 권한 ACL |
| MQTT Session | Spring `cleanStart=true` | 로봇 session expiry 0, 백엔드 clean start false + expiry 3600초 |
| Command Expiry | 미구현 | DB TTL + MQTT 5 Message Expiry |
| B DB Migration | 없음 | Robot·Telemetry·Command·Ingest Migration 추가 |
| 운영 Compose | `compose.prod.yaml`에 Merge Conflict 표식 존재 | Conflict 해결 후 `docker compose config` 통과 |

최종 계약 반영은 다음 파일을 한 번에 맞춰야 한다.

```text
web/backend/src/main/resources/application.yml
web/compose.yaml
web/.env.example
web/infra/mosquitto/acl
web/tools/mock-robot/mock_robot.py
web/tools/mock-robot/publish_test.py
Spring MQTT Properties·Connection Config·Contract Test
```

전역 QoS 하나로는 최종 계약을 표현할 수 없다. Spring Subscriber는 Topic별 QoS를 설정하고, Publisher는 Message Header 또는 전용 발행 메서드에서 QoS·Retain·Message Expiry를 Topic 종류에 따라 지정해야 한다.

---

## 15. 구현 우선순위

### Day 2 완료에 필요한 범위

- [ ] 최종 Topic Namespace와 Payload DTO 확정
- [ ] Local Mosquitto ACL을 `tbs/v1`으로 변경
- [ ] Spring에서 Topic별 QoS 구독
- [ ] Mock Robot Presence·State·Telemetry 발행
- [ ] Commands Request·Result Round Trip
- [ ] `robot`, `mission`, `robot_current_state`를 포함한 B Flyway Version 번호 합의
- [ ] Docker Compose에서 Spring·PostgreSQL·Mosquitto 실행

### Day 3 데이터 흐름

- [ ] Telemetry DTO 검증
- [ ] `robot_telemetry` 이력 INSERT
- [ ] 오래된 표본 방지 Current State UPSERT
- [ ] Robot Current State 조회 API
- [ ] Runtime Role 권한·중복 키 Smoke Test

### 실제 Jetson 통합 전 확인

- [ ] GPS 모듈 장착·Fix·HDOP 제공 여부
- [ ] 배터리 잔량 측정 회로 존재 여부
- [ ] 배터리 추정 사용 시 계산·valid·source 정책
- [ ] Jetson 온도 읽기 경로
- [ ] `boot_id`와 전 Topic 공용 `seq` 생성 위치
- [ ] 모델명·버전·bbox·damage ratio 제공 여부
- [ ] Image Upload API와 기기별 API Key
- [ ] Mission 상태머신과 완료 판정 기준
- [ ] 모터 오류를 실제로 판단할 수 있는 범위
- [ ] 기기별 MQTT username/password와 ACL 부정 테스트

---

## 16. 최종 결정 사항 요약

1. 최종 Topic은 `tbs/v1/robots/{robot_id}/...`를 사용한다.
2. Telemetry만 QoS 0이며 과거 값을 버퍼링·재전송하지 않는다.
3. Presence와 State만 Retain한다.
4. 사건 4종은 `event_id`를 기준으로 멱등 처리하고 로봇 Outbox에 저장한다.
5. 이미지 원본은 HTTPS로 업로드하고 MQTT에는 `image_id`만 넣는다.
6. MQTT의 `robot_id`, `mission_id`, `actor_id` 코드는 DB 내부 UUID로 변환한다.
7. Current State는 `sampled_at`이 더 최신일 때만 갱신하고 잘못된 GPS가 마지막 유효 위치를 덮지 않게 한다.
8. 임베디드의 실제 결함 유형은 `DAMAGED`, `MISSING`이며 나머지는 관리자 수정용이다.
9. 0.5초 안전 정지는 로컬 LiDAR 프로세스가 담당하고 MQTT는 사후 보고와 관리자 명령에 사용한다.
10. B의 최종 테이블은 DDL에 있으나 현재 Flyway에는 없으므로 별도 Version Migration이 필요하다.
11. DB는 Bootstrap, Flyway, Runtime Role을 분리하고 Runtime에는 최소 DML만 부여한다.
12. MQTT는 TLS, 기기별 계정, 최소 Topic ACL, 명령 TTL·Retain 금지를 적용한다.

---

## DB Registry·Mosquitto Drift 운영 정책 (2026-07-29)

Credential 정합성의 기준은 `robot.robot_credential`의 ACTIVE
`secret_hash`이며 Mosquitto `passwd`는 실행 Registry다.

| 상태 | 판정 | 수동 복구 |
| --- | --- | --- |
| `IN_SYNC` | DB ACTIVE hash와 Broker hash가 같거나 양쪽 모두 없음 | 변경 없음 |
| `MISSING` | DB ACTIVE는 있으나 Broker 계정 없음 | DB hash를 Broker에 복구 |
| `MISMATCH` | DB ACTIVE hash와 Broker hash가 다름 | DB hash를 Broker에 복구 |
| `ORPHANED` | DB ACTIVE는 없으나 Broker 계정이 남음 | Broker 계정 제거 |

- 회전·폐기 요청은 기대 hash가 다르면
  `MQTT_CREDENTIAL_REGISTRY_DRIFT`로 fail-closed한다.
- `mqtt.credential-provisioning.registry-scan-interval` 주기로 전체 로봇을
  재검사하며 기본값은 1분이다. 이 주기 검사가 장애 뒤 자동 재시도다.
- Metric은 `gilbom.mqtt.credential.registry.checks{result}`와
  `gilbom.mqtt.credential.registry.drift.current`,
  `gilbom.mqtt.credential.registry.inspection.errors.current`를 사용한다.
  robot ID, mqtt ID, credential ID, hash는 tag로 넣지 않는다.
- 운영 경보는 drift current 또는 inspection errors current가 0보다 큰
  상태로 2회 연속 수집될 때 발생시키고, 먼저 status API로 상태를 확인한
  뒤 승인된 관리자가 reconcile API를 호출한다.
- 수동 복구는 `ROBOT_REGISTER`, 객체 범위, CSRF를 적용하며
  `ROBOT_CREDENTIAL_REGISTRY_RECONCILED` 필수 감사를 남긴다. 감사 또는 DB
  트랜잭션이 실패하면 작업 전 Broker verifier 상태로 보상한다.
- 실제 TLS Cutover는
  `infra/mosquitto/local/verify-credential-cutover.sh`로 검증한다. 임시
  계정 외 기존 계정은 수정하지 않으며 평문 Secret을 출력하지 않는다.
- `S15P11D101-435`의 완료 근거는 로컬 Docker·TLS Mosquitto 검증이다.
  EC2 운영 환경에서는 공유 password-file 볼륨, UID/GID·파일 권한, TLS
  인증서·호스트명, Compose 네트워크, Secret 주입과 재배포 후 영속성을
  동일 절차로 다시 검증한다.
- Cutover 스크립트와 Credential 회전·폐기·수동 reconcile은 같은
  password-file을 변경하므로 동시에 실행하지 않는다. 실제 Jetson의 보호된
  Secret 교체와 재연결은 장비 통합 단계에서 별도로 확인한다.
