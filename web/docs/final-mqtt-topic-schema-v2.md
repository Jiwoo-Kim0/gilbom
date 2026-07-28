# 길봄 Final MQTT Topic Schema v2.0

> 문서 상태: 구현 계약안  
> Namespace: `tbs/v2/robots/{mqtt_id}/...`
> Payload Version: `"schema_version": "2.0"`  
> Encoding: UTF-8 JSON  
> JSON Key: `snake_case`  
> Time: ISO 8601 UTC `Z`

이 문서는 로봇·MQTT Broker·웹 백엔드가 주고받는 v2 MQTT Topic과 JSON Payload를 정의한다. 코드 구현 예시는 제외하고 실제 통신에 필요한 경로, 발행 조건, 데이터 형식과 서버 사용처를 중심으로 정리한다.

---

## 1. v2 변경 범위

v2에서는 실제 기기가 안정적으로 제공할 수 없는 값과 중복 필드를 제거한다.

### 제거한 필드

| 제거 필드 | 제거 이유 |
|---|---|
| `telemetry.environment` | 온습도 센서 제공 범위에서 제외 |
| `position.accuracy_m` | 기기가 신뢰 가능한 Meter 단위 정확도를 제공하지 않음 |
| `mission.result.distance_m` | 기기가 총거리·실제 이동거리를 제공하지 않음 |
| 미션 `progress_percent`·`total_distance_m`·`remaining_distance_m` | 기기가 총거리를 모르므로 계산하지 않음 |

### v2 핵심 결정

- DB 내부 로봇 식별자는 UUID `robot_id`이고 MQTT 외부 식별자는 문자열
  `mqtt_id`로 명확히 분리한다.
- `mqtt_id`는 서버가 로봇별로 발급하는 불변·고유 값이며 MQTT username,
  Topic 경로와 Payload 식별자에 동일하게 사용한다.
- MQTT 연결 세션 식별자는 별도의 `mqtt_client_id`를 사용한다.
- 기기는 미션 완료율과 예상 완료시간을 MQTT로 보내지 않는다.
- 미션 진행률이 필요하면 서버가 계획 경로와 유효 GPS 궤적을 이용해 별도로 계산한다.
- `position.heading_deg`는 이동 중 GPS RMC Course Over Ground가 유효할 때만 관제용으로 제공한다.
- Battery Event는 배터리 전이 이력이 필요한 경우에만 사용하는 선택 Topic이다.
- 짧은 장애물 정지는 State로만 알리고, 재확인 후에도 지속된 정지만 Safety Event로 저장한다.
- 위 제거 필드 외에는 v1의 Topic 이름, JSON 멤버 이름과 중첩 구조를 변경하지 않는다.
- 실제 MQTT Payload에는 설명용 주석을 넣지 않는다. 이 문서의 모든 `json` 블록은 주석 없는 JSON 예시다.

---

## 2. Topic 전체 목록

### 2.1 로봇 → 서버

| Topic | QoS | Retain | 발행 조건 | 주요 용도 |
|---|---:|:---:|---|---|
| `tbs/v2/robots/{mqtt_id}/presence` | 1 | O | 연결·종료·LWT | 온라인·오프라인 판정 |
| `tbs/v2/robots/{mqtt_id}/state` | 1 | O | 상태 전이 즉시 + 30초 | 현재 운행·미션·배터리·장치 상태 |
| `tbs/v2/robots/{mqtt_id}/telemetry` | 0 | X | 5초 주기 | GPS·배터리·Jetson 온도 이력 |
| `tbs/v2/robots/{mqtt_id}/events/incident` | 1 | X | 결함 관측 확정 시 1회 | 결함 관측·AI 결과 |
| `tbs/v2/robots/{mqtt_id}/events/battery` | 1 | X | 배터리 상태 전이 시 1회 | 선택: 저전력 전이 이력 |
| `tbs/v2/robots/{mqtt_id}/events/safety` | 1 | X | 지속 안전 사건 확정·해제 시 1회 | 장애물·센서 고장 사건 |
| `tbs/v2/robots/{mqtt_id}/events/mission` | 1 | X | 미션 상태 전이 시 1회 | 미션 시작·정지·완료 이력 |
| `tbs/v2/robots/{mqtt_id}/commands/result` | 1 | X | 명령 접수·처리 결과 발생 시 | 명령 ACK·성공·실패 |

### 2.2 서버 → 로봇

| Topic | QoS | Retain | 발행 조건 | 주요 용도 |
|---|---:|:---:|---|---|
| `tbs/v2/robots/{mqtt_id}/commands/request` | 1 | X | 관리자 명령 생성 시 | 미션·긴급정지 명령 전달 |
| `tbs/v2/robots/{mqtt_id}/ingest/receipt` | 1 | X | Event DB 처리 완료 시 | 심화: 서버 저장 결과 확인 |

### 2.3 방향별 경로

```json
{
  "robot_to_server": [
    "tbs/v2/robots/{mqtt_id}/presence",
    "tbs/v2/robots/{mqtt_id}/state",
    "tbs/v2/robots/{mqtt_id}/telemetry",
    "tbs/v2/robots/{mqtt_id}/events/incident",
    "tbs/v2/robots/{mqtt_id}/events/battery",
    "tbs/v2/robots/{mqtt_id}/events/safety",
    "tbs/v2/robots/{mqtt_id}/events/mission",
    "tbs/v2/robots/{mqtt_id}/commands/result"
  ],
  "server_to_robot": [
    "tbs/v2/robots/{mqtt_id}/commands/request",
    "tbs/v2/robots/{mqtt_id}/ingest/receipt"
  ]
}
```

---

## 3. 공통 통신 규칙

### 3.1 권장 데이터 형식

| 항목 | 계약 |
|---|---|
| Payload | UTF-8로 인코딩한 JSON Object |
| Content Type | `application/json` |
| Key 이름 | `snake_case` |
| Enum | 대문자 `UPPER_SNAKE_CASE` |
| 시각 | ISO 8601 UTC, 예: `2026-07-26T05:15:35.000Z` |
| UUID | 하이픈을 포함한 UUIDv4 문자열 |
| 위도·경도 | WGS84 Decimal Degree JSON Number |
| 배터리 | 퍼센트 `0.0~100.0` JSON Number |
| 온도 | 섭씨 JSON Number |
| 시간 길이 | 초 단위 JSON Integer |
| 사용할 수 없는 값 | 의미가 있는 경우 `null`, 불필요한 선택 필드는 생략 |

빈 문자열이나 임의의 `0`을 알 수 없는 값으로 사용하지 않는다.

```json
{
  "valid": false,
  "lat": null,
  "lon": null
}
```

### 3.2 식별자와 순서

| 멤버 | 타입 | 규칙 |
|---|---|---|
| `schema_version` | String | v2 Payload는 항상 `"2.0"` |
| `mqtt_id` | String | Topic의 `{mqtt_id}` 및 MQTT username과 일치 |
| `boot_id` | UUID String | 로봇 통신 프로그램 시작 시 한 번 생성 |
| `seq` | Integer | `1`부터 시작하며 한 `boot_id` 안에서 State·Telemetry·Event가 공유하는 증가 순번 |
| `message_id` | UUID String | Telemetry 표본 식별자 |
| `event_id` | UUID String | Event 재전송 중복 제거 식별자 |
| `command_id` | UUID String | 명령 요청과 결과 연결 식별자 |

`seq`는 새 `boot_id`가 만들어지면 `1`로 초기화한다. 로봇은 메시지를 새로
생성할 때마다 값을 1씩 증가시키고, 재전송할 때는 기존 값을 유지한다. Wire에서는
`(mqtt_id, boot_id, seq)`, DB 저장 후에는 `(robot_id, boot_id, seq)` 조합으로
순서와 중복을 확인한다.

### 3.3 MQTT Client 설정과 DB 매핑

로봇 MQTT Client는 최소 다음 설정을 사용한다.

```dotenv
MQTT_ID=RBT-001
MQTT_PASSWORD=replace-with-provisioned-secret
MQTT_CLIENT_ID=RBT-001-main
MQTT_CA_CERT_PATH=/etc/gilbom/certs/ca.crt
MQTT_BROKER_HOST=mqtt.example.com
MQTT_BROKER_PORT=8883
```

| 설정 | 역할 |
|---|---|
| `MQTT_ID` | Broker username, Topic `{mqtt_id}`, Payload `mqtt_id` |
| `MQTT_PASSWORD` | Broker 인증 Secret, DB 원문 저장 금지 |
| `MQTT_CLIENT_ID` | MQTT 연결 세션 식별자, `mqtt_id`와 다른 개념 |
| `MQTT_CA_CERT_PATH` | Broker 인증서를 검증할 CA 파일 경로 |
| `MQTT_BROKER_HOST` | TLS Broker hostname |
| `MQTT_BROKER_PORT` | TLS 포트, 기본 `8883` |

Backend는 Topic의 `mqtt_id`로 MQTT Identity를 조회한 뒤 내부 UUID `robot_id`로
변환하여 Telemetry·Event·Current State의 FK에 저장한다.

```text
mqtt_id "RBT-001"
→ robot MQTT Identity 조회
→ robot_id UUID
→ 업무·이력 테이블 FK 저장
```

MQTT Password 원문은 로봇의 Secret 설정과 발급 직후 1회 응답에만 존재한다.
DB에는 Credential verifier/hash, fingerprint, version, status와 수명주기만 저장한다.
CA 파일은 로봇별 업무 데이터가 아니라 배포 환경 설정으로 관리한다.

### 3.4 QoS와 Retain

| 데이터 성격 | QoS | Retain | 이유 |
|---|---:|:---:|---|
| Presence·State | 1 | O | 재연결 시 현재 상태 즉시 복원 |
| Telemetry | 0 | X | 최신 표본 우선, 과거 표본 재전송 불필요 |
| Event | 1 | X | 사건은 중복 제거 후 이력 저장 |
| Command | 1 | X | 제어 명령 유실 방지, 오래된 명령 재실행 금지 |
| Receipt | 1 | X | Event 처리 결과 전달 |

### 3.5 서버 공통 검증

- Payload의 `mqtt_id`는 Topic의 `{mqtt_id}`와 같아야 한다.
- Broker ACL은 인증된 MQTT username과 Topic의 `{mqtt_id}`가 같을 때만 발행을 허용한다.
- 지원하지 않는 `schema_version`은 처리하지 않는다.
- State는 기존 `updated_at`보다 새로운 경우에만 현재 상태를 갱신한다.
- Telemetry는 기존 `sampled_at`보다 오래된 표본이 현재값을 덮어쓰지 않게 한다.
- Event는 `event_id`로 멱등 처리한다.
- 조건부 값이 없다고 이전 값을 현재 Payload에 복사하지 않는다.
- 이미지 원본, Base64, S3 Key와 Presigned URL은 MQTT에 넣지 않는다.

---

## 4. Presence

### 4.1 경로와 용도

```text
tbs/v2/robots/{mqtt_id}/presence
```

| 항목 | 값 |
|---|---|
| 방향 | 로봇 → 서버, LWT는 Broker → 서버 |
| QoS | 1 |
| Retain | `true` |
| 서버 사용처 | 로봇 온라인·오프라인, 마지막 연결 시각, Boot Session |

Presence는 통신 연결 상태만 의미한다. 장치 고장이나 안전 정지는 State와 Event로 전달한다.

### 4.2 ONLINE JSON

```json
{
  "schema_version": "2.0",
  "mqtt_id": "RBT-001",
  "boot_id": "572b2e59-d31f-445f-a43d-7df80ba64203",
  "status": "ONLINE",
  "connected_at": "2026-07-26T05:00:00.000Z"
}
```

### 4.3 OFFLINE LWT JSON

```json
{
  "schema_version": "2.0",
  "mqtt_id": "RBT-001",
  "boot_id": "572b2e59-d31f-445f-a43d-7df80ba64203",
  "status": "OFFLINE",
  "reason": "UNGRACEFUL_DISCONNECT"
}
```

### 4.4 멤버

| 멤버 | 타입 | 필수 | 값·용도 |
|---|---|:---:|---|
| `schema_version` | String | O | `"2.0"` |
| `mqtt_id` | String | O | 등록된 MQTT 외부 식별자 |
| `boot_id` | UUID String | O | 현재 부팅 세션 |
| `status` | Enum String | O | `ONLINE`, `OFFLINE` |
| `connected_at` | Date-time String | 조건부 | ONLINE 연결 성공 시각 |
| `reason` | Enum String | 조건부 | OFFLINE 원인 |

OFFLINE `reason`:

```json
[
  "GRACEFUL_SHUTDOWN",
  "CRITICAL_BATTERY_SHUTDOWN",
  "SYSTEM_FAULT_SHUTDOWN",
  "UNGRACEFUL_DISCONNECT"
]
```

---

## 5. State

### 5.1 경로와 용도

```text
tbs/v2/robots/{mqtt_id}/state
```

| 항목 | 값 |
|---|---|
| 방향 | 로봇 → 서버 |
| QoS | 1 |
| Retain | `true` |
| 발행 | 연결 직후 1회, 상태 전이 즉시, 변화가 없어도 30초마다 |
| 서버 사용처 | `robot_current_state`, 대시보드 상태 카드, 실시간 상태 Push |

State는 “지금 로봇이 무엇을 하고 있고 왜 그런 상태인지”를 나타내는 최신 스냅샷이다.

### 5.2 권장 JSON

```json
{
  "schema_version": "2.0",
  "mqtt_id": "RBT-001",
  "boot_id": "572b2e59-d31f-445f-a43d-7df80ba64203",
  "seq": 84,
  "updated_at": "2026-07-26T05:15:35.000Z",
  "mode": "PATROLLING",
  "reason_code": "NONE",
  "mission": {
    "mission_id": "MIS-20260726-001",
    "state": "RUNNING"
  },
  "battery": {
    "valid": true,
    "percent": 67.4,
    "state": "NORMAL"
  },
  "health": {
    "camera": "OK",
    "lidar": "OK",
    "gps": "OK",
    "battery": "OK",
    "motor": "UNKNOWN",
    "storage": "OK"
  }
}
```

활성 미션이 없으면 `mission`은 `null`이다.

```json
{
  "mission": null
}
```

### 5.3 멤버

| 멤버 | 타입 | 필수 | 값·용도 |
|---|---|:---:|---|
| `schema_version` | String | O | `"2.0"` |
| `mqtt_id` | String | O | 등록된 MQTT 외부 식별자 |
| `boot_id` | UUID String | O | 현재 부팅 세션 |
| `seq` | Integer | O | 부팅 내 공용 증가 순번 |
| `updated_at` | Date-time String | O | 이 상태가 로봇에서 확정된 시각 |
| `mode` | Enum String | O | 현재 운행 모드 |
| `reason_code` | Enum String | O | 현재 모드가 된 주된 이유 |
| `mission` | Object 또는 Null | O | 활성 미션 상태 |
| `mission.mission_id` | String | 조건부 | 서버가 START_MISSION으로 전달한 코드 |
| `mission.state` | Enum String | 조건부 | 로봇 내부 미션 상태 |
| `battery.valid` | Boolean | O | 현재 배터리 잔량값의 사용 가능 여부 |
| `battery.percent` | Number 또는 Null | O | 현재 배터리 퍼센트, 무효하면 `null` |
| `battery.state` | Enum String | O | 현재 배터리 등급 |
| `health.camera` | Enum String | O | 카메라 입력 상태 |
| `health.lidar` | Enum String | O | LiDAR 연결·Scan 상태 |
| `health.gps` | Enum String | O | GPS 연결·Fix 상태 |
| `health.battery` | Enum String | O | 배터리 측정 경로 상태 |
| `health.motor` | Enum String | O | 모터 Driver·Feedback 상태 |
| `health.storage` | Enum String | O | 파일시스템·Outbox 상태 |

### 5.4 Enum

```json
{
  "mode": [
    "BOOTING",
    "READY",
    "PATROLLING",
    "PAUSED",
    "SAFE_STOP",
    "ERROR",
    "SHUTTING_DOWN"
  ],
  "reserved_mode": [
    "RETURNING_TO_CHARGE",
    "CHARGING"
  ],
  "reason_code": [
    "NONE",
    "REMOTE_PAUSE",
    "REMOTE_ESTOP",
    "OBSTACLE",
    "BOUNDARY_LOST",
    "LOW_BATTERY",
    "CRITICAL_BATTERY",
    "LIDAR_FAULT",
    "MOTOR_FAULT",
    "INTERNAL_ERROR"
  ],
  "mission_state": [
    "READY",
    "RUNNING",
    "PAUSED",
    "COMPLETED",
    "CANCELLED",
    "FAILED"
  ],
  "battery_state": [
    "NORMAL",
    "LOW",
    "CRITICAL",
    "UNKNOWN"
  ],
  "health": [
    "OK",
    "DEGRADED",
    "FAULT",
    "UNKNOWN"
  ]
}
```

Health 값은 임베디드가 장치 Driver 상태를 판정하여 제공한다.

| 값 | 의미 |
|---|---|
| `UNKNOWN` | 부팅 직후 검사 전이거나 현재 하드웨어로 정상 여부를 판단할 수 없음 |
| `OK` | 장치 연결과 최근 유효 데이터 수신이 정상 |
| `DEGRADED` | 장치는 연결됐지만 Fix·주기·Freshness 등이 정상 기준에 미달 |
| `FAULT` | 장치 연결 실패, 연속 읽기 실패 또는 Fault 제한시간 초과 |

| Health 멤버 | 주요 판정 신호 |
|---|---|
| `health.camera` | Camera Open, 최근 Frame, 연속 읽기 실패 |
| `health.lidar` | Serial·SDK 연결, 최근 Scan, 연속 Parse 실패 |
| `health.gps` | Serial 연결, 최근 NMEA, GPS Fix |
| `health.battery` | BMS·ADC 읽기 성공, 최근 측정값 |
| `health.motor` | I2C Driver 응답, Fault Feedback |
| `health.storage` | Filesystem 쓰기, 디스크 사용률, Outbox 저장 |

### 5.5 `battery.state`와 `health.battery`

```text
battery.state = LOW
health.battery = OK
```

배터리 측정 장치는 정상이고 실제 잔량이 낮다는 뜻이다.

```text
battery.state = UNKNOWN
health.battery = FAULT
```

측정 장치 오류로 잔량을 알 수 없다는 뜻이다.

### 5.6 장애물 State 발행

로봇은 장애물을 감지하면 MQTT 발행보다 모터 정지를 먼저 수행한다.

| 상황 | State | Safety Event |
|---|---|---|
| 장애물 최초 감지 | `SAFE_STOP/OBSTACLE` 즉시 발행 | 없음 |
| 재확인 전에 해제 | `PATROLLING/NONE` 발행 | 없음 |
| 재확인 후에도 지속 | 기존 `SAFE_STOP/OBSTACLE` 유지 | `SAFETY_STOPPED` 1회 |
| 확정 장애물 해제·우회 성공 | `PATROLLING/NONE` 발행 | `SAFETY_CLEARED` 1회 |

일시적인 자동 안전 정지는 미션 전체를 중단한 것이 아니므로 `mission.state=RUNNING`을 유지할 수 있다.

---

## 6. Telemetry

### 6.1 경로와 용도

```text
tbs/v2/robots/{mqtt_id}/telemetry
```

| 항목 | 값 |
|---|---|
| 방향 | 로봇 → 서버 |
| QoS | 0 |
| Retain | `false` |
| 발행 | 5초마다 최신 표본 |
| 재연결 | 과거 Telemetry 재전송 금지 |
| 서버 사용처 | 측정 이력, 지도 위치, 실제 순찰 궤적, 배터리·온도 카드 |

### 6.2 권장 JSON

```json
{
  "schema_version": "2.0",
  "message_id": "fb9c4472-226d-4473-b684-f0d8282292bd",
  "mqtt_id": "RBT-001",
  "boot_id": "572b2e59-d31f-445f-a43d-7df80ba64203",
  "seq": 4312,
  "sampled_at": "2026-07-26T05:15:35.000Z",
  "mission_id": "MIS-20260726-001",
  "position": {
    "valid": true,
    "lat": 37.4979,
    "lon": 127.0276,
    "heading_deg": 92.3
  },
  "motion": {
    "moving": true,
    "speed_mps": null
  },
  "battery": {
    "valid": true,
    "percent": 67.4,
    "state": "NORMAL",
    "source": "MEASURED"
  },
  "system": {
    "jetson_temp_c": 41.5
  },
  "mission": {
    "state": "RUNNING",
    "elapsed_sec": 583,
    "incident_count": 2
  }
}
```

GPS Fix가 없으면 과거 위치를 복사하지 않는다.

```json
{
  "position": {
    "valid": false,
    "lat": null,
    "lon": null,
    "heading_deg": null
  }
}
```

배터리 잔량을 읽을 수 없으면 다음처럼 전달한다.

```json
{
  "battery": {
    "valid": false,
    "percent": null,
    "state": "UNKNOWN",
    "source": null
  }
}
```

### 6.3 멤버

| 멤버 | 타입 | 필수 | 값·용도 |
|---|---|:---:|---|
| `schema_version` | String | O | `"2.0"` |
| `message_id` | UUID String | O | 표본마다 새로 생성 |
| `mqtt_id` | String | O | 등록된 MQTT 외부 식별자 |
| `boot_id` | UUID String | O | 현재 부팅 세션 |
| `seq` | Integer | O | 부팅 내 공용 증가 순번 |
| `sampled_at` | Date-time String | O | 센서 표본 구성 시각 |
| `mission_id` | String 또는 Null | O | 활성 미션 코드, 없으면 `null` |
| `position.valid` | Boolean | O | 현재 GPS Fix 유효 여부 |
| `position.lat` | Number 또는 Null | O | 유효한 위도 |
| `position.lon` | Number 또는 Null | O | 유효한 경도 |
| `position.heading_deg` | Number 또는 Null | O | GPS 이동 방향 `0.0 이상 360.0 미만`, 사용할 수 없으면 `null` |
| `motion.moving` | Boolean | O | 모터 주행 명령 기준 이동 여부 |
| `motion.speed_mps` | Number 또는 Null | O | 실제 속도, 현재 MVP는 `null` |
| `battery.valid` | Boolean | O | 배터리 잔량값의 사용 가능 여부 |
| `battery.percent` | Number 또는 Null | O | `0.0~100.0`, 무효하면 `null` |
| `battery.state` | Enum String | O | `NORMAL`, `LOW`, `CRITICAL`, `UNKNOWN` |
| `battery.source` | Enum String 또는 Null | O | `MEASURED`, `ESTIMATED`, 무효하면 `null` |
| `system.jetson_temp_c` | Number 또는 Null | O | Jetson Thermal Zone 최고 온도 |
| `mission` | Object 또는 Null | O | 활성 미션 상태·경과 집계 |
| `mission.state` | Enum String | 조건부 | 로봇 내부 미션 상태 |
| `mission.elapsed_sec` | Integer | 조건부 | 미션 시작 후 경과시간 |
| `mission.incident_count` | Integer | 조건부 | 현재 미션의 확정 Incident Event 수 |

### 6.4 값 생성 경계

| 값 | 기기 측 판단 |
|---|---|
| `position.valid` | 최신 GPS 데이터이며 Fix가 유효한지 확인 |
| `position.heading_deg` | 유효한 GPS RMC Course Over Ground를 사용하고 정지·저속·Course 미제공 시 `null` |
| `motion.moving` | 주행 PWM 명령 중이고 `PAUSED`, `SAFE_STOP`, `ERROR`가 아닌지 확인 |
| `motion.speed_mps` | 엔코더·오도메트리 측정값이 있을 때만 사용하며 현재 MVP는 `null` |
| `battery.valid` | 측정값이 정상 범위이고 최신인지 확인 |
| `battery.state` | 합의된 LOW·CRITICAL 임계값과 Hysteresis로 판정 |
| `system.jetson_temp_c` | 읽을 수 있는 Thermal Zone 중 최고값 |

`motion.moving`은 실제 바퀴 회전을 측정한 값이 아니라 모터 제어 명령 기준 값이다.

`position.heading_deg`는 로봇 본체가 바라보는 방향이 아니라 GPS가 계산한 지면 기준 이동 방향이다. 관제 지도 마커 회전에는 사용할 수 있지만, 정지 상태의 자세나 후진 방향을 포함한 자율주행 제어의 유일한 방향값으로 사용하지 않는다. 자율주행 자세 추정은 IMU·전자나침반·엔코더 또는 로컬 위치추정 결과와 결합한다.

실제 하드웨어가 제공할 때만 다음 Battery 확장 멤버를 사용할 수 있다. 멤버 이름은 v1과 같다.

```json
{
  "battery": {
    "valid": true,
    "percent": 67.4,
    "state": "NORMAL",
    "source": "MEASURED",
    "voltage_v": 15.8,
    "current_a": 1.2,
    "charging": false,
    "battery_temperature_c": 31.5,
    "estimated_runtime_sec": 5400
  }
}
```

| 선택 멤버 | 타입 | 사용 조건 |
|---|---|---|
| `battery.voltage_v` | Number | 실제 전압 측정 회로가 값을 제공할 때 |
| `battery.current_a` | Number | 실제 전류 센서가 값을 제공할 때 |
| `battery.charging` | Boolean | BMS·충전 감지 회로가 상태를 제공할 때 |
| `battery.battery_temperature_c` | Number | 배터리 온도 센서가 값을 제공할 때 |
| `battery.estimated_runtime_sec` | Integer | 보정된 잔여시간 모델이 있을 때 |

---

## 7. Event 공통 Envelope

Incident·Battery·Safety·Mission Event는 다음 공통 멤버를 사용한다.

```json
{
  "schema_version": "2.0",
  "event_id": "3cf98dc0-424a-4ef0-9423-57e53fcd72fc",
  "event_type": "EVENT_TYPE",
  "mqtt_id": "RBT-001",
  "boot_id": "572b2e59-d31f-445f-a43d-7df80ba64203",
  "seq": 1482,
  "source_component": "ai",
  "occurred_at": "2026-07-26T05:15:34.231Z",
  "sent_at": "2026-07-26T05:15:35.002Z",
  "mission_id": "MIS-20260726-001"
}
```

| 멤버 | 타입 | 필수 | 값·용도 |
|---|---|:---:|---|
| `schema_version` | String | O | `"2.0"` |
| `event_id` | UUID String | O | Event 멱등 처리 키 |
| `event_type` | Enum String | O | Topic별 사건 유형 |
| `mqtt_id` | String | O | 등록된 MQTT 외부 식별자 |
| `boot_id` | UUID String | O | 사건이 발생한 부팅 세션 |
| `seq` | Integer | O | 부팅 내 공용 증가 순번 |
| `source_component` | Enum String | O | `ai`, `power`, `safety`, `mission` |
| `occurred_at` | Date-time String | O | 최초 사건 발생 시각, 재전송해도 유지 |
| `sent_at` | Date-time String | O | 현재 MQTT 전송 시각 |
| `mission_id` | String 또는 Null | O | 관련 미션이 없으면 `null` |

Event를 재전송할 때 `event_id`와 `occurred_at`은 바꾸지 않는다.

---

## 8. Events — Incident

### 8.1 경로와 용도

```text
tbs/v2/robots/{mqtt_id}/events/incident
```

| 항목 | 값 |
|---|---|
| 방향 | 로봇 → 서버 |
| QoS | 1 |
| Retain | `false` |
| 발행 | Tracking·Cooldown을 통과해 결함 관측을 확정했을 때 1회 |
| 서버 사용처 | 결함 관측 저장, 기존 결함 병합, 이미지 연결 |

### 8.2 권장 JSON

```json
{
  "schema_version": "2.0",
  "event_id": "3cf98dc0-424a-4ef0-9423-57e53fcd72fc",
  "event_type": "BRAILLE_BLOCK_INCIDENT_DETECTED",
  "mqtt_id": "RBT-001",
  "boot_id": "572b2e59-d31f-445f-a43d-7df80ba64203",
  "seq": 1482,
  "source_component": "ai",
  "occurred_at": "2026-07-26T05:15:34.231Z",
  "sent_at": "2026-07-26T05:15:35.002Z",
  "mission_id": "MIS-20260726-001",
  "location": {
    "valid": true,
    "lat": 37.4979,
    "lon": 127.0276,
    "zone_id": "A-01"
  },
  "incident": {
    "asset_type": "BRAILLE_BLOCK",
    "block_type": "LINEAR",
    "defect_type": "DAMAGED",
    "confidence": 0.87,
    "attributes": {
      "damage_ratio": 0.32
    }
  },
  "evidence": {
    "image_id": "550e8400-e29b-41d4-a716-446655440000",
    "captured_at": "2026-07-26T05:15:34.120Z",
    "image_width": 1280,
    "image_height": 720,
    "bbox_norm": [
      0.16,
      0.42,
      0.81,
      0.95
    ]
  },
  "model": {
    "name": "tbs-braille-yolo",
    "version": "1.0.0"
  }
}
```

### 8.3 멤버

| 멤버 | 타입 | 필수 | 값·용도 |
|---|---|:---:|---|
| Event 공통 Envelope | Object Members | O | 7장 공통 규칙 |
| `location.valid` | Boolean | O | 관측 위치 사용 가능 여부 |
| `location.lat` | Number 또는 Null | O | 위도, 위치 무효 시 `null` |
| `location.lon` | Number 또는 Null | O | 경도, 위치 무효 시 `null` |
| `location.zone_id` | String 또는 Null | O | START_MISSION으로 받은 순찰 구역 코드 |
| `incident.asset_type` | Enum String | O | `BRAILLE_BLOCK` |
| `incident.block_type` | Enum String | O | `LINEAR`, `DOT`, `UNKNOWN` |
| `incident.defect_type` | Enum String | O | MVP `DAMAGED` |
| `incident.confidence` | Number | O | `0.0~1.0` |
| `incident.attributes.damage_ratio` | Number 또는 Null | 조건부 | 손상 비율 `0.0~1.0` |
| `evidence.image_id` | UUID String 또는 Null | O | 사전 업로드 이미지 식별자 |
| `evidence.captured_at` | Date-time String | O | 원본 프레임 촬영 시각 |
| `evidence.image_width` | Integer | O | 원본 이미지 너비 |
| `evidence.image_height` | Integer | O | 원본 이미지 높이 |
| `evidence.bbox_norm` | Number Array | O | `[x_min, y_min, x_max, y_max]`, 각 값 `0.0~1.0` |
| `model.name` | String | O | AI 모델 이름 |
| `model.version` | String | O | AI 모델 버전 |

```json
{
  "block_type": [
    "LINEAR",
    "DOT",
    "UNKNOWN"
  ],
  "defect_type": [
    "DAMAGED"
  ],
  "reserved_defect_type": [
    "CONTAMINATED",
    "UPLIFTED",
    "BLOCKED",
    "MISSING_SUSPECTED",
    "INSTALLATION_INVALID",
    "UNKNOWN_ANOMALY"
  ]
}
```

GPS 위치가 무효여도 결함 Event 자체는 발행할 수 있다.

```json
{
  "location": {
    "valid": false,
    "lat": null,
    "lon": null,
    "zone_id": "A-01"
  }
}
```

이미지 업로드가 실패하면 `image_id`만 `null`로 보내고 Event 발행을 계속한다.

---

## 9. Events — Battery

### 9.1 경로와 용도

```text
tbs/v2/robots/{mqtt_id}/events/battery
```

| 항목 | 값 |
|---|---|
| 방향 | 로봇 → 서버 |
| QoS | 1 |
| Retain | `false` |
| 발행 | `NORMAL → LOW`처럼 배터리 등급이 바뀔 때 1회 |
| 서버 사용처 | 선택: 저전력 경고 이력, 당시 측정값·조치 저장 |

이 Topic은 배터리 퍼센트를 주기적으로 전달하지 않는다. 현재 배터리 등급만 필요하고 과거 전이 이력을 저장하지 않는 MVP에서는 이 Topic을 생략할 수 있다.

### 9.2 권장 JSON

```json
{
  "schema_version": "2.0",
  "event_id": "4d42ee50-f8e2-45dc-b348-2b5dd72f83bf",
  "event_type": "BATTERY_LOW",
  "mqtt_id": "RBT-001",
  "boot_id": "572b2e59-d31f-445f-a43d-7df80ba64203",
  "seq": 1488,
  "source_component": "power",
  "occurred_at": "2026-07-26T05:18:10.000Z",
  "sent_at": "2026-07-26T05:18:10.100Z",
  "mission_id": "MIS-20260726-001",
  "previous_state": "NORMAL",
  "battery": {
    "valid": true,
    "percent": 19.8,
    "state": "LOW"
  },
  "threshold_pct": 20.0,
  "action_taken": {
    "mission_state": "PAUSED",
    "robot_mode": "SAFE_STOP"
  }
}
```

### 9.3 멤버

| 멤버 | 타입 | 필수 | 값·용도 |
|---|---|:---:|---|
| Event 공통 Envelope | Object Members | O | 7장 공통 규칙 |
| `previous_state` | Enum String | O | 전이 전 배터리 등급 |
| `battery.valid` | Boolean | O | 당시 측정값 유효 여부 |
| `battery.percent` | Number 또는 Null | O | 당시 배터리 퍼센트 |
| `battery.state` | Enum String | O | 전이 후 배터리 등급 |
| `threshold_pct` | Number 또는 Null | O | 전이를 발생시킨 임계값 |
| `action_taken.mission_state` | Enum String 또는 Null | O | 조치 후 미션 상태 |
| `action_taken.robot_mode` | Enum String | O | 조치 후 운행 모드 |

```json
{
  "event_type": [
    "BATTERY_LOW",
    "BATTERY_CRITICAL",
    "BATTERY_RECOVERED",
    "BATTERY_SENSOR_FAULT",
    "BATTERY_SENSOR_RECOVERED"
  ],
  "battery_state": [
    "NORMAL",
    "LOW",
    "CRITICAL",
    "UNKNOWN"
  ]
}
```

`RECOVERED`는 배터리 상태가 아니다. `LOW → NORMAL` 전이의 Event Type이 `BATTERY_RECOVERED`다.

---

## 10. Events — Safety

### 10.1 경로와 용도

```text
tbs/v2/robots/{mqtt_id}/events/safety
```

| 항목 | 값 |
|---|---|
| 방향 | 로봇 → 서버 |
| QoS | 1 |
| Retain | `false` |
| 발행 | 지속 안전 사건 확정·해제 시 각각 1회 |
| 서버 사용처 | 로봇 Event, 관제 알림, 관리자 확인·해제 이력 |

실제 모터 정지는 MQTT가 아니라 로봇 내부 안전 프로세스가 먼저 수행한다.

### 10.2 장애물 발행 규칙

```text
장애물 최초 감지
    → 모터 즉시 정지
    → State SAFE_STOP/OBSTACLE 발행
    → Safety Event는 아직 발행하지 않음

설정된 대기시간 후 재확인
    → 장애물 사라짐: State PATROLLING/NONE, Event 없음
    → 장애물 지속: SAFETY_STOPPED 1회

확정 장애물 해제 또는 우회 성공
    → State PATROLLING/NONE
    → SAFETY_CLEARED 1회
```

센서 프레임이나 거리 측정마다 Event를 반복 발행하지 않는다. `SAFETY_STOPPED`를 발행하지 않은 짧은 정지에는 `SAFETY_CLEARED`도 발행하지 않는다.

### 10.3 SAFETY_STOPPED JSON

```json
{
  "schema_version": "2.0",
  "event_id": "81fd9bd1-8639-47a2-82fd-9f26281dac68",
  "event_type": "SAFETY_STOPPED",
  "mqtt_id": "RBT-001",
  "boot_id": "572b2e59-d31f-445f-a43d-7df80ba64203",
  "seq": 1491,
  "source_component": "safety",
  "occurred_at": "2026-07-26T05:19:00.410Z",
  "sent_at": "2026-07-26T05:19:02.450Z",
  "mission_id": "MIS-20260726-001",
  "reason_code": "OBSTACLE",
  "measurement": {
    "distance_m": 0.72
  },
  "transition": {
    "mode_before": "PATROLLING",
    "mode_after": "SAFE_STOP"
  },
  "auto_resume_allowed": true
}
```

### 10.4 SAFETY_CLEARED JSON

```json
{
  "schema_version": "2.0",
  "event_id": "56cd19c7-bdf0-42e5-bfb4-458cd2f5b255",
  "event_type": "SAFETY_CLEARED",
  "mqtt_id": "RBT-001",
  "boot_id": "572b2e59-d31f-445f-a43d-7df80ba64203",
  "seq": 1498,
  "source_component": "safety",
  "occurred_at": "2026-07-26T05:19:08.120Z",
  "sent_at": "2026-07-26T05:19:08.160Z",
  "mission_id": "MIS-20260726-001",
  "reason_code": "OBSTACLE",
  "measurement": {
    "distance_m": 1.53
  },
  "transition": {
    "mode_before": "SAFE_STOP",
    "mode_after": "PATROLLING"
  },
  "auto_resume_allowed": true
}
```

### 10.5 멤버

| 멤버 | 타입 | 필수 | 값·용도 |
|---|---|:---:|---|
| Event 공통 Envelope | Object Members | O | 7장 공통 규칙 |
| `reason_code` | Enum String | O | 안전 정지·고장 이유 |
| `measurement.distance_m` | Number 또는 Null | O | LiDAR 장애물 거리, 해당 없으면 `null` |
| `transition.mode_before` | Enum String | O | 전이 전 운행 모드 |
| `transition.mode_after` | Enum String | O | 전이 후 운행 모드 |
| `auto_resume_allowed` | Boolean | O | 안전 조건 해제 후 로컬 자동 재개 허용 여부 |

```json
{
  "event_type": [
    "SAFETY_STOPPED",
    "SAFETY_CLEARED",
    "SENSOR_FAULT",
    "SENSOR_RECOVERED"
  ],
  "reason_code": [
    "OBSTACLE",
    "BOUNDARY_LOST",
    "REMOTE_ESTOP",
    "LOW_BATTERY",
    "CRITICAL_BATTERY",
    "LIDAR_FAULT",
    "MOTOR_FAULT",
    "INTERNAL_ERROR"
  ]
}
```

`auto_resume_allowed=true`는 장애물이 이미 사라졌다는 의미가 아니다. 안전 조건이 해제되면 관리자 명령 없이 로봇 내부 FSM이 주행을 재개할 수 있다는 의미다.

---

## 11. Events — Mission

### 11.1 경로와 용도

```text
tbs/v2/robots/{mqtt_id}/events/mission
```

| 항목 | 값 |
|---|---|
| 방향 | 로봇 → 서버 |
| QoS | 1 |
| Retain | `false` |
| 발행 | 미션 상태 전이 시 1회 |
| 서버 사용처 | 미션 상태·이력, 수행시간과 결함 사건 수 |

기기는 미션 총거리, 진행률, 남은 거리와 예상 완료시간을 보내지 않는다.

### 11.2 MISSION_COMPLETED JSON

```json
{
  "schema_version": "2.0",
  "event_id": "2133612d-c882-446f-a283-72b2597f173e",
  "event_type": "MISSION_COMPLETED",
  "mqtt_id": "RBT-001",
  "boot_id": "572b2e59-d31f-445f-a43d-7df80ba64203",
  "seq": 2910,
  "source_component": "mission",
  "occurred_at": "2026-07-26T06:20:00.000Z",
  "sent_at": "2026-07-26T06:20:00.120Z",
  "mission_id": "MIS-20260726-001",
  "result": {
    "duration_sec": 4182,
    "incident_count": 7,
    "normal_detection_count": 143,
    "completion_reason": "ROUTE_COMPLETED"
  }
}
```

시작·일시정지·재개 Event에서는 `result`를 생략한다.

```json
{
  "schema_version": "2.0",
  "event_id": "1e10253d-eabe-43a8-a425-2815a3d5e279",
  "event_type": "MISSION_PAUSED",
  "mqtt_id": "RBT-001",
  "boot_id": "572b2e59-d31f-445f-a43d-7df80ba64203",
  "seq": 1700,
  "source_component": "mission",
  "occurred_at": "2026-07-26T05:30:00.000Z",
  "sent_at": "2026-07-26T05:30:00.050Z",
  "mission_id": "MIS-20260726-001"
}
```

### 11.3 멤버

| 멤버 | 타입 | 필수 | 값·용도 |
|---|---|:---:|---|
| Event 공통 Envelope | Object Members | O | 7장 공통 규칙 |
| `result` | Object | 조건부 | 완료·취소·실패 Event에서 사용 |
| `result.duration_sec` | Integer | 조건부 | 실제 미션 수행시간 |
| `result.incident_count` | Integer | 조건부 | 확정 발행한 Incident Event 수 |
| `result.normal_detection_count` | Integer | 조건부 | 중복 제거 기준으로 확정한 정상 탐지 수 |
| `result.completion_reason` | String | 조건부 | 미션 완료·종료 이유 |

```json
{
  "event_type": [
    "MISSION_STARTED",
    "MISSION_PAUSED",
    "MISSION_RESUMED",
    "MISSION_COMPLETED",
    "MISSION_CANCELLED",
    "MISSION_FAILED"
  ]
}
```

`normal_detection_count`는 카메라 프레임 수가 아니다. Tracking·Cooldown 등 임베디드팀과 합의한 중복 제거 기준으로 확정한 정상 관측만 센다.

미션 진행률이 필요하면 서버가 관리하는 계획 경로와 `mission_id`가 같은 유효 Telemetry 위치를 사용한다. 이 서버 계산값은 MQTT v2 계약에 포함하지 않는다.

---

## 12. Commands — Request

### 12.1 경로와 용도

```text
tbs/v2/robots/{mqtt_id}/commands/request
```

| 항목 | 값 |
|---|---|
| 방향 | 서버 → 로봇 |
| QoS | 1 |
| Retain | `false` |
| MQTT 5 | Message Expiry 사용 |
| 서버 사용처 | 관리자 요청을 검증한 뒤 로봇에 제어 명령 전달 |

### 12.2 START_MISSION JSON

```json
{
  "schema_version": "2.0",
  "command_id": "a75d9cec-b04d-4f66-b18a-4c48782f805a",
  "command_type": "START_MISSION",
  "issued_at": "2026-07-26T05:30:00.000Z",
  "expires_at": "2026-07-26T05:31:00.000Z",
  "requested_by": {
    "actor_type": "ADMIN",
    "actor_id": "ADM-0042"
  },
  "parameters": {
    "mission_id": "MIS-20260726-001",
    "zone_id": "A-01"
  }
}
```

### 12.3 EMERGENCY_STOP JSON

```json
{
  "schema_version": "2.0",
  "command_id": "95626222-c5c8-47ea-901e-f9e467b275f6",
  "command_type": "EMERGENCY_STOP",
  "issued_at": "2026-07-26T05:21:00.000Z",
  "expires_at": "2026-07-26T05:21:10.000Z",
  "requested_by": {
    "actor_type": "ADMIN",
    "actor_id": "ADM-0042"
  },
  "parameters": {
    "reason": "operator_request"
  }
}
```

### 12.4 멤버

| 멤버 | 타입 | 필수 | 값·용도 |
|---|---|:---:|---|
| `schema_version` | String | O | `"2.0"` |
| `command_id` | UUID String | O | 명령 멱등 처리 키 |
| `command_type` | Enum String | O | 실행할 명령 |
| `issued_at` | Date-time String | O | 서버 발행 시각 |
| `expires_at` | Date-time String | O | 명령 만료 시각 |
| `requested_by.actor_type` | Enum String | O | `ADMIN`, `SYSTEM` |
| `requested_by.actor_id` | String | O | 관리자·시스템 식별자 |
| `parameters` | Object | O | 명령별 파라미터 |

### 12.5 명령과 Parameters

| `command_type` | `parameters` | 권장 TTL |
|---|---|---:|
| `START_MISSION` | `mission_id`, `zone_id` | 60초 |
| `PAUSE_MISSION` | `reason` | 30초 |
| `RESUME_MISSION` | 빈 Object | 30초 |
| `CANCEL_MISSION` | `reason` | 30초 |
| `EMERGENCY_STOP` | `reason` | 10초 |
| `CLEAR_EMERGENCY_STOP` | `reason` | 30초 |

예약 명령은 v1의 이름을 유지하지만 MVP에서는 발행하지 않는다.

```json
{
  "reserved_command_type": [
    "RETURN_TO_CHARGE",
    "UPDATE_CONFIG"
  ]
}
```

`expires_at`과 MQTT 5 Message Expiry는 같은 만료시각을 기준으로 설정한다. 로봇은 이미 처리한 `command_id`를 다시 실행하지 않는다.

---

## 13. Commands — Result

### 13.1 경로와 용도

```text
tbs/v2/robots/{mqtt_id}/commands/result
```

| 항목 | 값 |
|---|---|
| 방향 | 로봇 → 서버 |
| QoS | 1 |
| Retain | `false` |
| 발행 | 명령 접수 또는 최종 처리 상태가 바뀔 때 |
| 서버 사용처 | 명령 상태·ACK·실패·Timeout 표시 |

### 13.2 권장 JSON

```json
{
  "schema_version": "2.0",
  "result_id": "e72802e1-bf90-4f79-aa2a-c98d347710c6",
  "command_id": "95626222-c5c8-47ea-901e-f9e467b275f6",
  "mqtt_id": "RBT-001",
  "status": "COMPLETED",
  "received_at": "2026-07-26T05:21:00.130Z",
  "completed_at": "2026-07-26T05:21:00.240Z",
  "reason_code": "OK",
  "state": {
    "mode": "SAFE_STOP",
    "reason_code": "REMOTE_ESTOP"
  }
}
```

### 13.3 멤버

| 멤버 | 타입 | 필수 | 값·용도 |
|---|---|:---:|---|
| `schema_version` | String | O | `"2.0"` |
| `result_id` | UUID String | O | Result 메시지 식별자 |
| `command_id` | UUID String | O | 원본 명령 식별자 |
| `mqtt_id` | String | O | 등록된 MQTT 외부 식별자 |
| `status` | Enum String | O | 명령 처리 상태 |
| `received_at` | Date-time String | O | 로봇이 명령을 받은 시각 |
| `completed_at` | Date-time String 또는 Null | O | 최종 처리 시각, ACCEPTED면 `null` |
| `reason_code` | Enum String | O | 처리 결과 이유 |
| `state.mode` | Enum String | O | 명령 처리 후 운행 모드 |
| `state.reason_code` | Enum String | O | 명령 처리 후 상태 이유 |

```json
{
  "status": [
    "ACCEPTED",
    "COMPLETED",
    "FAILED",
    "REJECTED",
    "EXPIRED"
  ],
  "reason_code_example": [
    "OK",
    "SAFETY_CONDITION_NOT_CLEARED",
    "NO_ACTIVE_MISSION",
    "ALREADY_IN_STATE",
    "INTERNAL_ERROR"
  ]
}
```

`ACCEPTED`와 최종 결과를 모두 발행하면 각 메시지는 서로 다른 `result_id`를 사용하고 동일한 `command_id`로 연결한다.

---

## 14. Ingest — Receipt

### 14.1 경로와 용도

```text
tbs/v2/robots/{mqtt_id}/ingest/receipt
```

| 항목 | 값 |
|---|---|
| 방향 | 서버 → 로봇 |
| QoS | 1 |
| Retain | `false` |
| 범위 | MVP 이후 심화 |
| 서버 사용처 | Event DB 저장·결함 병합 결과를 로봇에 확인 |

PUBACK은 Broker가 메시지를 받았다는 뜻이다. Receipt는 백엔드가 Event를 검증하고 DB Transaction을 완료했다는 애플리케이션 수준 결과다.

### 14.2 권장 JSON

```json
{
  "schema_version": "2.0",
  "event_id": "3cf98dc0-424a-4ef0-9423-57e53fcd72fc",
  "ingest_status": "STORED",
  "incident_id": "INC-20260726-000031",
  "received_at": "2026-07-26T05:15:35.150Z"
}
```

### 14.3 멤버

| 멤버 | 타입 | 필수 | 값·용도 |
|---|---|:---:|---|
| `schema_version` | String | O | `"2.0"` |
| `event_id` | UUID String | O | 로봇이 보낸 원본 Event |
| `ingest_status` | Enum String | O | DB 처리 결과 |
| `incident_id` | String 또는 Null | O | Incident 저장·병합 결과, 다른 Event는 `null` |
| `received_at` | Date-time String | O | 서버가 Event를 수신·처리한 시각 |

```json
{
  "ingest_status": [
    "STORED",
    "MERGED",
    "DUPLICATE",
    "REJECTED"
  ]
}
```

MVP에서 Receipt를 사용하지 않으면 Event QoS 1 PUBACK를 기준으로 로봇 Outbox를 정리한다. Receipt를 사용하면 DB 처리 결과를 받을 때까지 Outbox를 유지하고, Timeout 시 같은 `event_id`로 재전송한다.

---

## 15. 데이터 사용처 요약

| Topic | 백엔드 처리 | 프론트·업무 사용 |
|---|---|---|
| Presence | 로봇 연결·Boot Session 갱신 | 온라인·오프라인 배지 |
| State | 최신 상태 UPSERT | 운행 모드, 정지 이유, 미션·배터리·Health 카드 |
| Telemetry | 이력 INSERT, 유효 최신값 갱신 | 지도 위치·이동 방향, 궤적, 배터리 %, Jetson 온도 |
| Incident Event | 관측 저장, 결함 생성·병합 | 결함 지도·목록·상세 |
| Battery Event | 선택: 전이 이력 저장 | 저전력 경고 이력 |
| Safety Event | 안전 사건 저장 | 이벤트 목록, 알림, 관리자 확인 |
| Mission Event | 상태 이력·최종 결과 저장 | 미션 상세·상태 이력 |
| Command Request | 명령 DB 생성 후 MQTT Publish | 제어 요청 |
| Command Result | 명령 결과·ACK 저장 | 성공·실패·Timeout 표시 |
| Ingest Receipt | 심화: DB 처리 결과 Publish | 로봇 Outbox 정리 |

---

## 16. 최종 구현 규칙

1. v1 Topic과 v2 Topic을 같은 Payload 계약으로 혼용하지 않는다.
2. v2 발행자는 Topic을 `tbs/v2/...`로 바꾸고 `schema_version`을 문자열 `"2.0"`으로 보낸다.
3. Presence와 State만 Retain한다.
4. Telemetry는 최신 표본만 발행하며 재연결 후 과거 표본을 보내지 않는다.
5. Event는 `event_id`를 유지하여 QoS 1 재전송을 멱등 처리한다.
6. State는 현재값, Telemetry는 측정값, Event는 저장할 사건이라는 역할을 섞지 않는다.
7. GPS가 무효하면 `valid=false`, `lat=null`, `lon=null`로 보낸다.
8. GPS가 정지·저속 상태이거나 Course를 제공하지 않으면 `heading_deg=null`로 보낸다.
9. 미션 진행률·거리·ETA는 기기 MQTT Payload에 넣지 않는다.
10. 실제 안전 정지는 로봇 내부에서 먼저 수행하고 MQTT는 상태와 확정 사건을 보고한다.
11. 명령에는 Retain을 사용하지 않고 TTL과 MQTT 5 Message Expiry를 적용한다.
