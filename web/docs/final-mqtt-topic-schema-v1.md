# 길봄 Final MQTT Topic Schema v1.0

> **DEPRECATED / SUPERSEDED:** 이 문서는 과거 v1 계약 보관본이다. 신규 구현과 검증은
> [`final-mqtt-topic-schema-v2.md`](./final-mqtt-topic-schema-v2.md),
> [`asyncapi-mqtt-v2.yaml`](./asyncapi-mqtt-v2.yaml), `mqtt-v2/schemas/`를 따른다.
> v1 namespace와 payload를 신규 배포에 사용하지 않는다.

> MQTT Topic과 JSON Payload 구조만 정리한 문서  
> Namespace: `tbs/v1/robots/{robot_id}/...`  
> JSON Key: `snake_case`  
> Time: ISO 8601 UTC `Z`  
> 실제 MQTT 메시지에는 아래 예시의 `payload` Object만 발행한다.  
> 코드 블록의 `#` 뒤 내용은 값 후보를 보여주는 문서용 주석이다. 실제 발행 JSON에서는 `#` 주석을 제거한다.

---

## 1. Presence

Presence는 로봇과 서버가 실제로 업무 데이터를 주고받는 상태인지 알려주는 **통신 생존 상태 Topic**이다.

- 로봇 부팅·MQTT 연결 성공: `ONLINE`
- 정상 종료: 로봇이 직접 `OFFLINE`
- 통신 두절·전원 차단·프로세스 Crash: Broker가 LWT `OFFLINE`
- 마지막 Presence는 Retain되므로 백엔드가 재시작해도 현재 온라인 상태를 즉시 복원

Presence는 주행 상태를 의미하지 않는다. 로봇이 장애물 때문에 멈췄어도 MQTT 연결이 유지되면 Presence는 `ONLINE`이고, 정지 원인은 State·Safety Event로 전달한다.

부팅·연결 순서는 다음으로 고정한다.

```text
1. 로봇 프로세스 시작
2. 새로운 boot_id 생성
3. Broker에 UNGRACEFUL_DISCONNECT LWT 등록
4. MQTT Broker 연결
5. 연결 성공 후 ONLINE Presence 발행
```

연락이 끊기면 다음 순서로 처리된다.

```text
1. LTE·Wi-Fi 단절, 전원 차단 또는 프로세스 Crash
2. Broker가 Keep Alive Timeout으로 연결 끊김 감지
3. Broker가 미리 등록된 OFFLINE LWT 발행
4. 백엔드가 로봇을 OFFLINE으로 변경
5. Retained Presence가 OFFLINE으로 교체
```

### 1.1 Topic

```text
{
  "topic": "tbs/v1/robots/RBT-001/presence",
  "direction": "ROBOT_TO_SERVER",       # ONLINE·정상 OFFLINE은 로봇 발행, LWT OFFLINE은 Broker 발행
  "qos": 1,                             # 후보: 1
  "retain": true                        # 후보: true
}
```

### 1.2 ONLINE — 로봇이 살아서 연결됨

```text
{
  "topic": "tbs/v1/robots/RBT-001/presence",
  "direction": "ROBOT_TO_SERVER",
  "qos": 1,
  "retain": true,
  "payload": {
    "schema_version": "1.0",            # 후보: 1.0
    "robot_id": "RBT-001",              # 후보: 등록된 robot_id
    "boot_id": "572b2e59-d31f-445f-a43d-7df80ba64203",
    "status": "ONLINE",                 # 후보: ONLINE | OFFLINE
    "connected_at": "2026-07-26T05:00:00.000Z"
  }
}
```

- MQTT 연결 직후 로봇이 직접 발행한다.
- `connected_at`은 ONLINE에서만 사용한다.
- ONLINE에는 `reason`을 넣지 않는다.

### 1.3 OFFLINE — 로봇이 종료 이유를 알고 직접 발행

```text
{
  "topic": "tbs/v1/robots/RBT-001/presence",
  "direction": "ROBOT_TO_SERVER",
  "qos": 1,
  "retain": true,
  "payload": {
    "schema_version": "1.0",
    "robot_id": "RBT-001",
    "boot_id": "572b2e59-d31f-445f-a43d-7df80ba64203",
    "status": "OFFLINE",                # 후보: ONLINE | OFFLINE
    "reason": "CRITICAL_BATTERY_SHUTDOWN"
                                            # 후보: GRACEFUL_SHUTDOWN
                                            #       CRITICAL_BATTERY_SHUTDOWN
                                            #       SYSTEM_FAULT_SHUTDOWN
  }
}
```

- 정상 종료, 치명적 배터리 부족, 복구 불가능한 상태 이상처럼 로봇이 종료 원인을 알고 있을 때 직접 발행한다.
- OFFLINE에는 `connected_at`을 넣지 않는다.

### 1.4 OFFLINE — 통신 두절·전원 차단·프로세스 비정상 종료 LWT

```text
{
  "topic": "tbs/v1/robots/RBT-001/presence",
  "direction": "BROKER_TO_SERVER",
  "qos": 1,
  "retain": true,
  "payload": {
    "schema_version": "1.0",
    "robot_id": "RBT-001",
    "boot_id": "572b2e59-d31f-445f-a43d-7df80ba64203",
    "status": "OFFLINE",                # 후보: ONLINE | OFFLINE
    "reason": "UNGRACEFUL_DISCONNECT"   # 후보: UNGRACEFUL_DISCONNECT
  }
}
```

- 로봇이 연결할 때 이 Payload를 Broker에 LWT로 미리 등록한다.
- LTE·Wi-Fi 통신 두절, 전원 차단, 프로세스 강제 종료·Crash 시 Broker가 대신 발행한다.
- LWT는 끊기기 전에 미리 등록하므로 동적으로 배터리나 장애 원인을 판단할 수 없다.

### 1.5 Presence Reason 후보

```text
{
  "reason_candidates": [
    "GRACEFUL_SHUTDOWN",                 # 정상 종료
    "CRITICAL_BATTERY_SHUTDOWN",         # 치명적 배터리 부족으로 안전 종료
    "SYSTEM_FAULT_SHUTDOWN",              # 복구 불가능한 상태·시스템 이상으로 종료
    "UNGRACEFUL_DISCONNECT"               # 통신 두절·전원 차단·프로세스 Crash, Broker LWT
  ]
}
```

위 블록은 후보 목록이며 네 개를 동시에 발행하는 JSON이 아니다. 실제 Payload에는 상황에 맞는 `reason` 하나만 넣는다.

연결이 비정상적으로 **끝난 이유**를 나타내므로 `UNGRACEFUL_CONNECTION`보다 `UNGRACEFUL_DISCONNECT`가 의미상 정확하다.

> 배터리가 LOW이지만 MQTT 연결이 살아 있으면 Presence는 `ONLINE`이다. 이 경우 `events/battery`와 State의 `SAFE_STOP/LOW_BATTERY`로 보고한다. 상태 이상으로 정지했지만 통신이 살아 있는 경우도 Presence는 `ONLINE`이고 State 또는 Safety Event로 보고한다. 실제로 종료·연결 단절될 때만 Presence를 `OFFLINE`으로 바꾼다.

---

## 2. 공통 규칙

모든 Topic이 같은 방식으로 식별자·시각·JSON Key를 사용하도록 정한 공통 계약이다. 로봇과 서버는 개별 Payload를 처리하기 전에 이 규칙부터 검증한다.

```text
{
  "schema_version": "1.0",              # 후보: 1.0
  "robot_id": "RBT-001"                 # 후보: 등록된 robot_id
}
```

- `robot_id`는 Topic의 `{robot_id}` 및 MQTT username과 같아야 한다.
- `boot_id`는 로봇 프로그램이 시작될 때마다 새 UUIDv4를 생성한다.
- `seq`는 필드를 실제로 포함하는 State·Telemetry·Event 4종이 한 `boot_id` 안에서 공유하는 증가 순번이다. Presence와 현재 Commands Result Schema에는 `seq`가 없다.
- 사용하지 않는 조건부 필드는 빈 문자열로 보내지 않고 생략하거나 `null`을 사용한다.
- 이미지 원본·Base64·S3 경로·Presigned URL은 MQTT로 보내지 않는다.
- 명령 Topic은 Retain을 절대 사용하지 않는다.

---

## 3. State

로봇이 **현재 어떤 운행 상태이고 왜 그 상태인지** 알려주는 스냅샷 Topic이다. 관제 화면은 이 값을 이용해 순찰 중·일시정지·안전정지와 센서별 정상·고장 상태를 표시한다.

### State를 보내는 시점

```text
1. MQTT 연결 성공
2. ONLINE Presence 발행
3. 현재 State 최초 1회 발행
4. 이후 상태가 바뀌면 즉시 발행
5. 상태 변화가 없어도 30초마다 현재 State 발행
```

즉시 State를 다시 발행해야 하는 변화:

```text
mode 변경
    READY → PATROLLING
    PATROLLING → PAUSED
    PATROLLING → SAFE_STOP
    SAFE_STOP → PATROLLING
    임의 상태 → ERROR

reason_code 변경
    NONE → OBSTACLE
    NONE → LOW_BATTERY
    OBSTACLE → NONE

mission.state 변경
    READY → RUNNING
    RUNNING → PAUSED
    PAUSED → RUNNING
    RUNNING → COMPLETED

battery.state 변경
    NORMAL → LOW
    LOW → CRITICAL
    LOW → NORMAL

health.* 변경
    OK → DEGRADED
    DEGRADED → FAULT
    FAULT → OK
```

배터리가 `67.4% → 67.3%`로 바뀌었지만 `battery.state=NORMAL`이 유지되면 State를 즉시 발행할 필요가 없다. 퍼센트 변화는 5초 Telemetry가 전달하고, State는 `NORMAL → LOW`처럼 의미 있는 상태 전이가 생길 때 즉시 발행한다.

관리자 명령과 로컬 안전 동작에서도 다음처럼 사용한다.

| 상황 | 함께 발행하는 메시지 |
|---|---|
| `START_MISSION` 실행 | Commands Result + `PATROLLING/RUNNING` State + Mission Event |
| `PAUSE_MISSION` 실행 | Commands Result + `PAUSED` State + Mission Event |
| 장애물 발견·정지 | `SAFE_STOP/OBSTACLE` State + Safety Event |
| 장애물 제거·재출발 | `PATROLLING/NONE` State + Safety Event |
| 배터리 LOW 진입 | `SAFE_STOP/LOW_BATTERY` State + Battery Event |
| 복구 불가능한 오류 | `ERROR` State + 해당 Safety·Battery Event |

### State와 Telemetry 차이

| 구분 | State | Telemetry |
|---|---|---|
| 핵심 질문 | 지금 무엇을 하고 있고 왜 그런가? | 지금 측정한 값이 얼마인가? |
| 주요 값 | mode, reason, mission state, battery state, health | GPS, 배터리 %, 온도, 속도 |
| 발행 주기 | 상태 전이 즉시 + 30초 | 5초마다 |
| QoS | 1 | 0 |
| Retain | O | X |
| 통신 복구 | Broker가 마지막 State 즉시 전달 | 과거 표본 재전송 안 함 |
| 서버 용도 | 현재 상태 복원·상태 표시 | 측정 이력·지도·그래프 |

```text
State
= "로봇은 통신 중이고, 장애물 때문에 SAFE_STOP 상태다."

Telemetry
= "현재 위치는 위도·경도이고, 배터리는 67.4%, Jetson 온도는 41.5°C다."
```

마지막 Retained State가 `PATROLLING`이어도 Presence가 `OFFLINE`이면 실제 화면에는 OFFLINE을 우선 표시한다.

```text
{
  "topic": "tbs/v1/robots/RBT-001/state",
  "direction": "ROBOT_TO_SERVER",
  "qos": 1,
  "retain": true,
  "publish_rule": "상태 전이 시 즉시 발행 + 30초 주기",
  "payload": {
    "schema_version": "1.0",
    "robot_id": "RBT-001",
    "boot_id": "572b2e59-d31f-445f-a43d-7df80ba64203",
    "seq": 84,
    "updated_at": "2026-07-26T05:15:35.000Z",
    "mode": "PATROLLING",               # 후보: BOOTING | READY | PATROLLING | PAUSED | SAFE_STOP | ERROR | SHUTTING_DOWN
    "reason_code": "NONE",              # 후보: NONE | REMOTE_PAUSE | REMOTE_ESTOP | OBSTACLE | BOUNDARY_LOST
                                            #       LOW_BATTERY | CRITICAL_BATTERY | LIDAR_FAULT | MOTOR_FAULT | INTERNAL_ERROR
    "mission": {
      "mission_id": "MIS-20260726-001",
      "state": "RUNNING"                # 후보: READY | RUNNING | PAUSED | COMPLETED | CANCELLED | FAILED
    },
    "battery": {
      "valid": true,
      "percent": 67.4,
      "state": "NORMAL"                 # 후보: NORMAL | LOW | CRITICAL | UNKNOWN
    },
    "health": {
      "camera": "OK",                   # 임베디드 판정: 장치 Open + 최근 Camera Frame 수신 시각
      "lidar": "OK",                    # 임베디드 판정: Serial·SDK 연결 + 최근 Scan 수신 시각
      "gps": "OK",                      # 임베디드 판정: NMEA 수신 시각 + GPS Fix 상태
      "battery": "OK",                  # 임베디드 판정: 잔량 회로 읽기 성공 + 값 Freshness
      "motor": "UNKNOWN",               # 임베디드 판정: I2C Driver 응답, 현재 Feedback 센서 없어 정상 확정 불가
      "storage": "OK"                   # 임베디드 판정: 디스크 쓰기 가능 + 사용률 + Outbox 저장 성공
                                            # 모든 health 후보: OK | DEGRADED | FAULT | UNKNOWN
    }
  }
}
```

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
  "health": [
    "OK",
    "DEGRADED",
    "FAULT",
    "UNKNOWN"
  ]
}
```

### `health` 값 생성·수신 책임

`health`는 웹 서버가 Telemetry의 측정값을 이용해 임의로 계산하는 값이 아니다. 로봇의 임베디드 프로그램이 각 센서·장치 Driver의 연결 상태, 최근 데이터 수신 시각, 연속 오류 횟수 등을 확인하여 `OK/DEGRADED/FAULT/UNKNOWN` 중 하나로 판정한 뒤 State Payload에 포함한다.

```text
센서·장치 Driver 상태 확인
    → 임베디드가 health.* 판정
    → tbs/v1/robots/{robot_id}/state 발행
    → 웹 백엔드 MQTT Consumer 수신
    → robot_current_state 최신 상태 UPSERT
    → SSE 또는 WebSocket으로 관리자 화면에 전달
```

#### 역할 구분

| 구분 | 책임 |
|---|---|
| 임베디드팀 | 장치별 상태 신호 수집, 임계값 적용, `health.*` 최종 판정 및 MQTT 발행 |
| 웹팀 B 백엔드 | State Payload 검증·역직렬화, `updated_at` 기준 최신 상태 저장, 실시간 Push |
| 웹팀 B 프론트엔드 | 수신된 상태를 정상·주의·고장·확인 불가로 표시 |

웹 백엔드는 다음 Topic을 구독하여 `payload.health`를 그대로 가져온다.

```text
tbs/v1/robots/+/state
```

```json
{
  "robot_id": "RBT-001",
  "updated_at": "2026-07-26T05:15:35.000Z",
  "health": {
    "camera": "OK",
    "lidar": "OK",
    "gps": "DEGRADED",
    "battery": "OK",
    "motor": "UNKNOWN",
    "storage": "OK"
  }
}
```

Java DTO 예:

```java
public record RobotStateMessage(
    String robotId,
    Instant updatedAt,
    Health health
) {
    public record Health(
        HealthStatus camera,
        HealthStatus lidar,
        HealthStatus gps,
        HealthStatus battery,
        HealthStatus motor,
        HealthStatus storage
    ) {}
}

public enum HealthStatus {
    OK,
    DEGRADED,
    FAULT,
    UNKNOWN
}
```

서버는 로봇이 보낸 `health.*` 값을 정상 상황에서 다시 계산하거나 덮어쓰지 않는다. 다만 다음 검증은 수행한다.

- 허용되지 않은 Enum 값이면 해당 State 메시지를 거부하거나 해당 필드를 `UNKNOWN`으로 처리하고 오류를 기록한다.
- 기존 `updated_at`보다 오래된 메시지는 `robot_current_state`를 덮어쓰지 않고 이력에만 저장한다.
- Presence가 `OFFLINE`이면 화면에서는 Retained State의 `health`보다 통신 단절 상태를 우선 표시한다.
- `health` 필드가 없거나 개별 장치 값이 없으면 추측하여 `OK`로 만들지 않고 `UNKNOWN`으로 처리한다.

#### `battery.state`와 `health.battery` 구분

`battery.state`는 실제 잔량 수준이고, `health.battery`는 잔량을 읽는 센서·측정 경로의 정상 여부다.

```text
battery.state = LOW + health.battery = OK
    # 센서는 정상이며 실제 배터리 잔량이 낮음

battery.state = UNKNOWN + health.battery = FAULT
    # 센서 오류로 현재 배터리 잔량을 알 수 없음
```

### 센서별 `health.*` 판정 방법

> 아래 시간·횟수·사용률은 초기 제안값이다. 웹팀 B는 `OK/DEGRADED/FAULT/UNKNOWN` 의미와 Payload만 계약하고, 실제 Sensor Driver 신호와 임계값은 임베디드팀이 실측 후 환경설정으로 확정한다.

#### 공통 상태 의미

| 값 | 판정 의미 |
|---|---|
| `UNKNOWN` | 부팅 직후 아직 검사하지 않았거나 현재 하드웨어로 정상 여부를 판단할 수 없음 |
| `OK` | 장치 연결과 최근 유효 데이터 수신이 모두 정상 |
| `DEGRADED` | 장치는 연결됐지만 Fix·속도·정확도·Freshness 등이 정상 기준에 미달 |
| `FAULT` | 장치 연결 실패, 연속 읽기 실패 또는 Fault 제한시간 초과 |

#### Camera

확인할 신호:

```text
장치 Open 성공 여부
최근 Frame 수신 시각
연속 Frame Read 실패 횟수
실제 Frame Rate
```

| 상태 | 초기 판정 후보 |
|---|---|
| `UNKNOWN` | 프로그램 시작 후 첫 Frame을 아직 받지 못함 |
| `OK` | Camera Open 성공 + 마지막 Frame이 2초 이내 + Frame Decode 정상 |
| `DEGRADED` | 마지막 Frame이 2~5초 전 또는 FPS가 합의된 최저값 미만 |
| `FAULT` | Camera Open 실패, 마지막 Frame 5초 초과 또는 연속 읽기 실패 |

```text
"camera": "OK"
    # 정보 획득: USB/CSI Camera Driver의 isOpened, last_frame_at, consecutive_read_failures, measured_fps
    # 임베디드 확인: CAMERA_STALE_SEC, CAMERA_FAULT_SEC, 최소 FPS
```

`health.camera`는 영상 입력 장치 상태다. AI 추론 모델의 정확도·Inference 오류까지 Camera 상태로 섞을지는 임베디드팀과 별도 합의가 필요하다.

#### LiDAR

확인할 신호:

```text
USB Serial Port Open 여부
YDLidar SDK 연결 상태
최근 Scan 수신 시각
Scan Frequency
연속 Parse·Read 오류
```

| 상태 | 초기 판정 후보 |
|---|---|
| `UNKNOWN` | SDK 초기화 중, 첫 Scan 대기 |
| `OK` | Serial·SDK 연결 + 마지막 Scan이 1초 이내 |
| `DEGRADED` | Scan은 오지만 1~3초 지연 또는 Scan Frequency 저하 |
| `FAULT` | Serial·SDK 연결 실패, 마지막 Scan 3초 초과 또는 연속 오류 |

```text
"lidar": "FAULT"
    # 정보 획득: YDLidar SDK connection flag, last_scan_at, scan_frequency, read_error_count
    # FAULT 동작: 모터 로컬 정지 → State SAFE_STOP 또는 ERROR → SENSOR_FAULT Safety Event
    # 임베디드 확인: LIDAR_STALE_SEC, LIDAR_FAULT_SEC, 최소 Scan Frequency
```

#### GPS

확인할 신호:

```text
UART·USB Serial Open 여부
최근 NMEA 문장 수신 시각
RMC Status A/V
GGA Fix Quality
HDOP·위치 정확도
```

| 상태 | 초기 판정 후보 |
|---|---|
| `UNKNOWN` | GPS 초기화·첫 NMEA 대기 |
| `OK` | 최근 NMEA 수신 + RMC `A` + GGA Fix Quality 1 이상 |
| `DEGRADED` | NMEA는 오지만 Fix 없음, HDOP 불량 또는 정확도 기준 미달 |
| `FAULT` | Serial Open 실패, NMEA Parse 연속 실패 또는 10초 이상 NMEA 없음 |

```text
"gps": "DEGRADED"
    # 정보 획득: NMEA last_received_at, RMC status, GGA fix_quality, HDOP
    # Telemetry 연계: GPS가 DEGRADED·FAULT이면 position.valid=false
    # 임베디드 확인: GPS_STALE_SEC, GPS_FAULT_SEC, 허용 HDOP·accuracy_m
```

GPS 장치는 정상인데 위성 Fix만 없는 경우는 `FAULT`가 아니라 `DEGRADED`다.

#### Battery

확인할 신호:

```text
BMS·ADC·잔량 회로 존재 여부
최근 측정 성공 시각
측정값 0~100 범위
센서 오류 코드
시간 기반 추정 사용 여부
```

| 상태 | 초기 판정 후보 |
|---|---|
| `UNKNOWN` | 잔량 측정 회로 유무가 미확정이거나 아직 값을 읽지 못함 |
| `OK` | 실제 측정값이 정상 범위이고 최근 값임 |
| `DEGRADED` | 실제 회로 대신 시간 기반 추정값만 제공하거나 값이 Stale 경고 구간 |
| `FAULT` | 측정 회로 읽기 실패, 범위 밖 값 또는 Fault 제한시간 초과 |

```text
"battery": "DEGRADED"
    # 정보 획득: BMS/ADC read result, last_sample_at, percent range, source
    # Telemetry 연계: FAULT이면 battery.valid=false, percent=null, state=UNKNOWN
    # 임베디드 확인: 잔량 회로 존재 여부, BATTERY_STALE_SEC, 추정값을 DEGRADED로 볼지
```

배터리 잔량이 LOW인 것과 배터리 센서가 고장난 것은 다르다.

```text
battery.state = LOW + health.battery = OK
    # 센서는 정상이며 실제 잔량이 낮음

battery.state = UNKNOWN + health.battery = FAULT
    # 센서 오류로 잔량을 알 수 없음
```

#### Motor

현재 MVP에는 엔코더·전류 피드백이 없으므로 실제 바퀴 회전과 모터 고착을 확인할 수 없다.

확인 가능한 신호:

```text
MotorHat I2C Address 응답 여부
모터 Driver 초기화 성공 여부
PWM Write 성공·예외
Driver가 제공하는 Fault Pin·상태값 존재 여부
```

| 상태 | 초기 판정 후보 |
|---|---|
| `UNKNOWN` | Driver 명령은 가능하지만 실제 모터 회전 Feedback이 없어 정상 여부를 확정할 수 없음 |
| `OK` | Encoder·Current·Fault Feedback이 추가되어 실제 동작 확인이 가능할 때만 사용 |
| `DEGRADED` | Driver 통신은 되지만 일부 Feedback을 사용할 수 없음 |
| `FAULT` | I2C 장치 미응답, Driver 초기화 실패, PWM Write 연속 실패 또는 Fault 신호 |

```text
"motor": "UNKNOWN"
    # 정보 획득: I2C 0x40 응답, Driver initialize 결과, PWM write exception
    # 현재 한계: 엔코더·전류 Feedback 없음 → 명령 성공만으로 OK 확정 금지
    # 임베디드 확인: MotorHat Fault 신호 제공 여부
```

#### Storage

확인할 신호:

```text
Filesystem Read/Write 가능 여부
Disk 사용률
Outbox DB Insert·Commit 성공 여부
Outbox 적재 건수·용량
```

| 상태 | 초기 판정 후보 |
|---|---|
| `UNKNOWN` | Storage 검사 전 |
| `OK` | Filesystem 쓰기 가능 + 사용률 80% 미만 + Outbox 저장 정상 |
| `DEGRADED` | 사용률 80~90% 또는 Outbox 적재량 경고 기준 초과 |
| `FAULT` | Read-only, Disk Full, 사용률 90% 초과 또는 Outbox 저장 실패 |

```text
"storage": "OK"
    # 정보 획득: disk_usage, writable check, SQLite Outbox transaction result, queue size
    # 임베디드 확인: STORAGE_WARNING_PCT, STORAGE_FAULT_PCT, Outbox 허용 크기
```

#### 상태 떨림 방지

센서값이 한 번 실패했다고 즉시 `OK ↔ FAULT`를 반복하지 않도록 연속 실패·성공 횟수를 적용한다.

```text
OK → DEGRADED
    # Stale Warning 시간 초과 또는 연속 실패 후보 3회

DEGRADED → FAULT
    # Fault 제한시간 초과 또는 장치 연결 실패

FAULT → OK
    # 장치 재연결 후 연속 정상 후보 5회
```

```text
# 임베디드팀 최종 확인 항목
HEALTH_FAILURE_COUNT
HEALTH_RECOVERY_COUNT
CAMERA_STALE_SEC / CAMERA_FAULT_SEC
LIDAR_STALE_SEC / LIDAR_FAULT_SEC
GPS_STALE_SEC / GPS_FAULT_SEC
BATTERY_STALE_SEC / BATTERY_FAULT_SEC
STORAGE_WARNING_PCT / STORAGE_FAULT_PCT
```

위 값은 코드에 하드코딩하지 않고 임베디드 환경설정으로 관리하는 것을 권장한다.

---

## 4. Telemetry

GPS·배터리·온도처럼 계속 변하는 **센서 측정값을 5초마다 전달**하는 Topic이다. 서버는 측정 이력을 저장하고 최신 유효값으로 지도 위치와 관제 카드를 갱신한다.

```text
{
  "topic": "tbs/v1/robots/RBT-001/telemetry",
  "direction": "ROBOT_TO_SERVER",       # 웹팀 B 계약: 로봇이 발행하고 서버가 구독
  "qos": 0,                             # 웹팀 B 계약: 최신값 우선, 과거값 재전송 안 함
  "retain": false,                      # 웹팀 B 계약: 최신 상태 복원은 State가 담당
  "publish_rule": "5초 주기, 재연결 시 과거 표본 재전송 금지",
  "payload": {
    "schema_version": "1.0",            # 웹팀 B 제공: MQTT Payload 계약 버전
    "message_id": "fb9c4472-226d-4473-b684-f0d8282292bd",
                                            # 임베디드 제공: 표본마다 UUIDv4 생성
    "robot_id": "RBT-001",              # 서버 발급 후 임베디드 설정 파일·환경변수에 주입
    "boot_id": "572b2e59-d31f-445f-a43d-7df80ba64203",
                                            # 임베디드 제공: 통신 프로그램 부팅 시 UUIDv4 생성
    "seq": 4312,                        # 임베디드 제공: State·Telemetry·Event 공용 Atomic Counter
    "sampled_at": "2026-07-26T05:15:35.000Z",
                                            # 임베디드 제공: Jetson NTP 동기화 UTC 시각
    "mission_id": "MIS-20260726-001",   # 서버→로봇 START_MISSION 명령에서 받은 값, 임무 없으면 null
    "position": {
      "valid": true,                    # 임베디드 판단: GPS Fix 유효 여부, 판단 기준 확인 필요
      "lat": 37.4979,                   # 임베디드 제공: GPS NEO-M8N NMEA 위도
      "lon": 127.0276,                  # 임베디드 제공: GPS NEO-M8N NMEA 경도
      "accuracy_m": 3.8,                # 임베디드 제공 후보: GPS Driver 또는 HDOP 환산, 제공 가능 여부 확인
      "heading_deg": 92.3               # 임베디드 제공 후보: GPS Course, 제공 불가하면 null
    },
    "motion": {
      "moving": true,                   # 임베디드 제공: 모터 주행·정지 제어 상태 기준 추정
      "speed_mps": null                 # 임베디드 확인 필요: 엔코더 없음, 현재는 null 권장
    },
    "battery": {
      "valid": true,                    # 임베디드 판단: 배터리 잔량 회로·추정값 신뢰 가능 여부
      "percent": 67.4,                  # 임베디드 확인 필요: 잔량 회로 있으면 측정, 없으면 추정 정책 합의
      "state": "NORMAL",                # 후보: NORMAL | LOW | CRITICAL | UNKNOWN
                                            # 임베디드 계산: percent와 합의된 임계값으로 판정
      "source": "ESTIMATED"             # 후보: MEASURED | ESTIMATED, 임베디드가 값 출처 표시
    },
    "system": {
      "jetson_temp_c": 41.5             # 임베디드 제공: Jetson Linux Thermal Zone 최고 온도
    },
    "environment": {
      "temperature_c": 29.1,            # 임베디드 제공: 온습도 센서, 장착·Driver 여부 확인
      "humidity_pct": 56.2              # 임베디드 제공: 온습도 센서, 제공 불가하면 null·생략
    },
    "mission": {
      "state": "RUNNING",               # 후보: READY | RUNNING | PAUSED | COMPLETED | CANCELLED | FAILED
                                            # 임베디드 제공: 로봇 내부 Mission 상태머신
      "elapsed_sec": 583,               # 임베디드 제공: Mission 시작 후 경과시간
      "incident_count": 2               # 임베디드 제공: 현재 Mission에서 확정한 Incident 수
    }
  }
}
```

GPS Fix가 없는 경우:

```json
{
  "position": {
    "valid": false,
    "lat": null,
    "lon": null,
    "accuracy_m": null,
    "heading_deg": null
  }
}
```

실제 하드웨어가 제공할 때만 사용할 수 있는 Battery 확장 필드:

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

### 4.1 임베디드팀 확인용 Telemetry 멤버 체크리스트

> 아래 방법은 웹팀 B가 임베디드 구현을 확정하는 기준이 아니다. 웹팀 B는 필요한 Payload 형식과 유효·무효 처리 계약을 제시하고, 실제 센서값 획득 방법과 판정 기준은 임베디드팀이 확인·확정한다.

#### 최상위 멤버

| 멤버 | 생성·확인 방법 | 값이 없거나 비정상일 때 |
|---|---|---|
| `schema_version` | 로봇 설정 파일의 MQTT 계약 버전 사용 | 지원 버전이 없으면 MQTT 프로그램 시작 실패 |
| `message_id` | Telemetry 표본을 만들 때마다 UUIDv4 새로 생성 | 생성 실패 시 해당 표본 발행 안 함 |
| `robot_id` | 기기 환경변수·설정 파일에서 읽음 | 빈 값이면 Broker 연결·발행 금지 |
| `boot_id` | 로봇 프로그램 시작 시 UUIDv4 한 번 생성하고 종료까지 유지 | 생성 실패 시 프로그램 시작 실패 |
| `seq` | 부팅 후 State·Telemetry·Event가 공유하는 Atomic Counter에서 Payload 생성 시 한 번 할당 | 새 `boot_id`를 만들 때 0부터 다시 시작 |
| `sampled_at` | 센서 표본을 구성한 순간의 Jetson 시스템 UTC 시각 | NTP 동기화 전에는 운영 발행을 시작하지 않는 것을 원칙으로 함 |
| `mission_id` | 마지막으로 승인·실행한 `START_MISSION` 명령의 `parameters.mission_id` 보관 | 활성 임무가 없으면 `null` |

#### `position`

| 멤버 | 생성·확인 방법 | 값이 없거나 비정상일 때 |
|---|---|---|
| `position.valid` | GPS RMC Status가 `A`이고 GGA Fix Quality가 1 이상이며 마지막 수신값이 허용 시간 이내인지 확인 | 조건 미충족 시 `false` |
| `position.lat` | 유효한 NMEA GGA·RMC 위도를 Decimal Degree로 변환 | `valid=false`이면 `null` |
| `position.lon` | 유효한 NMEA GGA·RMC 경도를 Decimal Degree로 변환 | `valid=false`이면 `null` |
| `position.accuracy_m` | GPS Driver가 정확도를 제공하면 사용. 없으면 팀이 승인한 HDOP 환산식이 있을 때만 계산 | 산출 근거가 없으면 `null` |
| `position.heading_deg` | 유효한 GPS RMC Course Over Ground 사용. 정지 상태나 Course 미제공 시 사용하지 않음 | `null` |

`position.valid=false`인데 이전 정상 GPS 좌표를 현재 표본에 복사해서 보내면 안 된다. 서버가 보관 중인 마지막 유효 위치는 유지하되, 현재 GPS가 유효하지 않다는 사실은 `valid=false`로 전달한다.

#### `motion`

| 멤버 | 생성·확인 방법 | 값이 없거나 비정상일 때 |
|---|---|---|
| `motion.moving` | 모터 제어기가 주행 PWM을 출력 중이고 `SAFE_STOP`, `PAUSED`, `ERROR` 상태가 아닌지 확인 | 정지 명령·안전정지 상태이면 `false` |
| `motion.speed_mps` | 엔코더·오도메트리로 실제 속도를 측정할 때 사용 | 현재 MVP는 엔코더가 없으므로 `null` |

현재 `motion.moving`은 실제 바퀴 회전 측정값이 아니라 **모터 제어 명령 기준 추정값**이다. 모터가 걸렸거나 헛돌아도 이를 확정할 센서가 없다는 한계가 있다.

#### `battery`

| 멤버 | 생성·확인 방법 | 값이 없거나 비정상일 때 |
|---|---|---|
| `battery.valid` | 잔량 측정 회로의 최근 값이 정상 범위·허용 시간 안인지 확인. 시간 기반 추정은 보정 정책이 확정된 경우에만 유효 처리 | 회로 없음·읽기 실패·오래된 값이면 `false` |
| `battery.percent` | BMS·ADC 측정값 또는 승인된 가동시간 기반 추정식으로 0~100 계산 | `valid=false`이면 `null` |
| `battery.state` | `percent > 20`은 `NORMAL`, `10 < percent <= 20`은 `LOW`, `percent <= 10`은 `CRITICAL` | `valid=false`이면 `UNKNOWN` |
| `battery.source` | 실제 회로 측정이면 `MEASURED`, 가동시간 추정이면 `ESTIMATED` | `valid=false`이면 `null` |
| `battery.voltage_v` | 전압 측정 회로가 실제 값을 제공할 때만 사용 | 회로가 없으면 생략 |
| `battery.current_a` | 전류 센서가 실제 값을 제공할 때만 사용 | 센서가 없으면 생략 |
| `battery.charging` | 충전 감지 회로 또는 BMS 상태가 제공될 때만 사용 | 확인할 수 없으면 생략 |
| `battery.battery_temperature_c` | 배터리 온도 센서가 제공될 때만 사용 | 센서가 없으면 생략 |
| `battery.estimated_runtime_sec` | 보정된 소비전력·잔량 모델이 있을 때 계산 | 모델이 없으면 생략 |

배터리 CRITICAL 10% 기준은 실측 후 확정할 값이다. 확정 전에는 환경설정으로 분리하고 코드에 하드코딩하지 않는다.

#### `system`

| 멤버 | 생성·확인 방법 | 값이 없거나 비정상일 때 |
|---|---|---|
| `system.jetson_temp_c` | `/sys/class/thermal/thermal_zone*/temp`에서 읽을 수 있는 Thermal Zone 값을 섭씨로 변환하고 최고값 사용 | 모든 Zone 읽기 실패 시 해당 Telemetry 발행 실패로 기록하고 State를 `ERROR/INTERNAL_ERROR`로 전이 |

Linux Thermal Zone 값이 `41500`이면 밀리도 단위이므로 `41.5°C`로 변환한다.

#### `environment`

| 멤버 | 생성·확인 방법 | 값이 없거나 비정상일 때 |
|---|---|---|
| `environment.temperature_c` | 온습도 센서 Driver의 최근 정상 온도값 사용 | 센서 미장착·읽기 실패·오래된 값이면 `null` 또는 멤버 생략 |
| `environment.humidity_pct` | 온습도 센서 Driver의 최근 정상 습도값 사용, 0~100 범위 검증 | 범위 밖·읽기 실패이면 `null` 또는 멤버 생략 |

#### `mission`

| 멤버 | 생성·확인 방법 | 값이 없거나 비정상일 때 |
|---|---|---|
| `mission.state` | 로봇 내부 Mission 상태머신의 현재 상태 사용 | 활성 임무가 없으면 `null` |
| `mission.elapsed_sec` | Mission 시작 시 저장한 monotonic clock과 현재 monotonic clock의 차이 | 활성 임무가 없으면 `null` |
| `mission.incident_count` | 현재 Mission에서 확정 발행한 Incident Event 수를 증가시켜 관리 | 활성 임무가 없으면 `null` |

#### 현재 MVP에서 실제로 채울 수 있는 수준

```text
{
  "position": {
    "valid": false,                     # GPS 장착·Fix 전에는 false
    "lat": null,
    "lon": null,
    "accuracy_m": null,
    "heading_deg": null
  },
  "motion": {
    "moving": true,                     # 모터 명령 기준 추정
    "speed_mps": null                   # 엔코더 없음
  },
  "battery": {
    "valid": false,                     # 잔량 회로 확인 전
    "percent": null,
    "state": "UNKNOWN",
    "source": null
  },
  "system": {
    "jetson_temp_c": 41.5               # Jetson에서 직접 확인 가능
  },
  "environment": {
    "temperature_c": null,              # 센서 연결 후 사용
    "humidity_pct": null
  }
}
```

### 4.2 참고 구현 후보 — 임베디드팀 확정 필요

아래 코드는 정보 획득 경로를 설명하기 위한 Python 형태의 참고 의사코드다. 웹팀 구현 요구사항이 아니며 센서 Driver, 임계값, 정확도 계산식과 실제 적용 여부는 임베디드팀이 확정한다.

#### 1. 기기 식별자와 버전

```python
import os
import uuid

SCHEMA_VERSION = "1.0"
ROBOT_ID = os.environ["ROBOT_ID"]
BOOT_ID = str(uuid.uuid4())
```

- `schema_version`: MQTT 계약이 바뀔 때만 변경하는 고정 설정값이다.
- `robot_id`: 기기별 환경변수나 읽기 전용 설정 파일에서 가져온다. 소스코드에 `RBT-001`을 하드코딩하지 않는다.
- `boot_id`: 로봇 통신 프로세스가 시작될 때 한 번 생성하며 같은 프로세스가 실행되는 동안 유지한다.

#### 2. `message_id`와 `seq`

```python
import threading
import uuid

class BootSequence:
    def __init__(self):
        self._next_value = 0
        self._lock = threading.Lock()

    def next(self):
        with self._lock:
            value = self._next_value
            self._next_value += 1
            return value

boot_sequence = BootSequence()

def new_message_identity():
    return {
        "message_id": str(uuid.uuid4()),
        "seq": boot_sequence.next(),
    }
```

- `message_id`: Telemetry 표본마다 새 UUIDv4를 생성한다.
- `seq`: State·Telemetry·Event가 같은 Counter를 공유한다. Publisher마다 별도 Counter를 만들면 전 Topic 공용 순번이 되지 않는다.
- 일반 정수에 `seq += 1`만 사용하면 여러 비동기 Thread가 같은 값을 가져가는 Race Condition이 생길 수 있으므로 Lock 또는 단일 발행 Queue가 필요하다.
- 위 `threading.Lock` 예시는 한 Process 안의 여러 Thread만 보호한다. 안전·AI·Mission이 서로 다른 Process라면 이 Lock을 공유할 수 없다.
- 여러 Process 구조에서는 각 Process가 MQTT를 직접 발행하지 않고, 하나의 MQTT Gateway Process에 IPC Queue로 발행 요청을 전달하는 방식을 권장한다.
- MQTT Gateway의 단일 Consumer가 Queue에서 하나씩 꺼내 `seq`를 할당한 뒤 Outbox 저장·Publish를 수행하면 전 Topic 순서와 원자성을 함께 보장할 수 있다.
- Event 재전송은 새로운 메시지가 아니므로 기존 `event_id`와 `seq`를 유지한다. 재전송할 때 Counter를 다시 증가시키지 않는다.
- `seq`는 Gateway가 메시지를 직렬화한 순서다. 실제 사건 발생 순서는 Event의 `occurred_at`으로 판단한다.
- QoS 0 Telemetry 유실 때문에 서버에서 `seq`가 건너뛰는 것은 정상적으로 발생할 수 있다.
- 새 `boot_id`를 만들면 `seq`는 0부터 다시 시작한다.

권장 구조:

```text
State Producer ─────┐
Telemetry Producer ─┼─ IPC Queue → MQTT Gateway 1개 → seq 할당 → Outbox/Publish
AI Event Producer ──┤
Safety Producer ────┤
Mission Producer ───┘
```

#### 3. `sampled_at`과 NTP

```python
from datetime import datetime, timezone

def utc_now():
    return (
        datetime.now(timezone.utc)
        .isoformat(timespec="milliseconds")
        .replace("+00:00", "Z")
    )
```

Jetson에서 NTP 동기화 여부를 먼저 확인한다.

```bash
timedatectl show --property=NTPSynchronized --value
```

결과가 `yes`일 때 운영 Telemetry 발행을 시작한다. `sampled_at`은 MQTT Publish 시각이 아니라 각 센서값을 하나의 표본으로 묶은 시각이다.

#### 4. `mission_id`

```python
active_mission_id = None

def handle_start_mission(command):
    global active_mission_id
    active_mission_id = command["parameters"]["mission_id"]

def handle_mission_finished():
    global active_mission_id
    active_mission_id = None
```

- 서버의 `START_MISSION` 명령으로 받은 값을 그대로 보관한다.
- 로봇이 임의로 Mission ID를 생성하지 않는다.
- 활성 임무가 없으면 `mission_id=null`로 보낸다.

#### 5. GPS `position`

GPS NEO-M8N의 NMEA 문장 중 다음 값을 사용한다.

| NMEA | 사용할 값 |
|---|---|
| RMC | Status, 위도, 경도, Course Over Ground |
| GGA | Fix Quality, 위도, 경도, HDOP |

```python
def build_position(rmc, gga, gps_age_sec):
    valid = (
        rmc is not None
        and gga is not None
        and rmc.status == "A"
        and gga.fix_quality >= 1
        and gps_age_sec <= GPS_STALE_SEC
    )

    if not valid:
        return {
            "valid": False,
            "lat": None,
            "lon": None,
            "accuracy_m": None,
            "heading_deg": None,
        }

    return {
        "valid": True,
        "lat": gga.latitude_decimal,
        "lon": gga.longitude_decimal,
        "accuracy_m": calculate_accuracy(gga.hdop),
        "heading_deg": calculate_heading(rmc),
    }
```

`accuracy_m` 처리 우선순위:

1. GPS Driver가 Meter 단위 정확도를 직접 제공하면 사용
2. 팀이 현장 실측으로 확정한 `HDOP × GPS_UERE_M` 환산식이 있으면 계산
3. 둘 다 없으면 정확한 것처럼 임의 계산하지 않고 `null`

```python
def calculate_accuracy(hdop):
    if hdop is None or GPS_UERE_M is None:
        return None
    return hdop * GPS_UERE_M
```

`heading_deg`는 이동 중인 유효 GPS Course가 있을 때만 사용한다.

```python
def calculate_heading(rmc):
    if rmc.course_deg is None or rmc.speed_mps < HEADING_MIN_SPEED_MPS:
        return None
    return rmc.course_deg % 360
```

정지 중에는 GPS Course가 흔들릴 수 있으므로 Heading을 `null`로 둔다.

#### 6. `motion`

현재 MVP에는 엔코더가 없으므로 모터 제어 상태로 `moving`을 추정한다.

```python
def build_motion(motor_command, robot_mode):
    moving = (
        motor_command.pwm > 0
        and robot_mode not in {"PAUSED", "SAFE_STOP", "ERROR", "SHUTTING_DOWN"}
    )

    return {
        "moving": moving,
        "speed_mps": None,
    }
```

`moving=true`는 바퀴가 실제로 회전한다는 보장이 아니라 모터에 주행 명령을 내리고 있다는 뜻이다. 실제 속도는 추후 엔코더가 생기면 다음 형태로 계산한다.

```text
speed_mps
= (초당 encoder pulse / 바퀴 1회전 pulse 수)
× 바퀴 둘레(m)
```

#### 7. 측정형 `battery`

BMS나 ADC가 실제 잔량을 제공하는 경우:

```python
def build_measured_battery(sample, sample_age_sec):
    valid = (
        sample is not None
        and sample_age_sec <= BATTERY_STALE_SEC
        and 0 <= sample.percent <= 100
    )

    if not valid:
        return {
            "valid": False,
            "percent": None,
            "state": "UNKNOWN",
            "source": None,
        }

    return {
        "valid": True,
        "percent": sample.percent,
        "state": classify_battery(sample.percent),
        "source": "MEASURED",
    }
```

ADC 전압만 있는 경우에는 전압을 0~100으로 단순 선형 변환하지 않는다. 리튬 배터리는 부하와 방전 구간에 따라 전압 곡선이 비선형이므로 현장 실측으로 만든 Lookup Table을 사용한다.

```python
percent = interpolate_percent(
    measured_voltage,
    BATTERY_VOLTAGE_PERCENT_TABLE
)
```

#### 8. 추정형 `battery`

잔량 회로가 없고 가동시간 기반 추정을 사용하기로 합의한 경우:

```python
def estimate_battery_percent(elapsed_sec, calibrated_runtime_sec):
    ratio = elapsed_sec / calibrated_runtime_sec
    return max(0.0, min(100.0, 100.0 * (1.0 - ratio)))
```

```python
{
    "valid": True,
    "percent": estimate_battery_percent(
        elapsed_sec,
        CALIBRATED_RUNTIME_SEC,
    ),
    "state": classify_battery(percent),
    "source": "ESTIMATED",
}
```

`CALIBRATED_RUNTIME_SEC`는 예상치가 아니라 실제 완충 주행 테스트로 구한다. 보정이 끝나지 않았다면 `valid=false`를 유지한다.

배터리 상태와 회복 히스테리시스:

```python
def classify_battery(percent):
    if percent <= CRITICAL_BATTERY_PCT:
        return "CRITICAL"
    if percent <= LOW_BATTERY_PCT:
        return "LOW"
    return "NORMAL"
```

- 권장 초기 설정: LOW 20%
- CRITICAL 10%와 RECOVERED 25%는 실측 후 확정
- LOW에서 20%를 조금 넘었다고 즉시 NORMAL로 바꾸지 않고 회복 기준 이상일 때만 복귀

#### 9. `system.jetson_temp_c`

Jetson Linux Thermal Zone을 읽는다.

```python
from pathlib import Path

def read_jetson_temperature():
    values = []

    for path in Path("/sys/class/thermal").glob("thermal_zone*/temp"):
        try:
            raw = float(path.read_text().strip())
            celsius = raw / 1000.0 if raw > 1000 else raw
            values.append(celsius)
        except (OSError, ValueError):
            continue

    if not values:
        raise SensorReadError("No readable Jetson thermal zone")

    return max(values)
```

- Thermal Zone의 `41500`은 `41.5°C`로 변환한다.
- 읽을 수 있는 Zone의 최고 온도를 사용한다.
- 모든 Zone 읽기에 실패하면 임의 기본 온도를 보내지 않는다.

#### 10. `environment`

센서 모델별 Driver가 반환한 최근 값을 사용한다.

```python
def build_environment(sample, sample_age_sec):
    if sample is None or sample_age_sec > ENV_STALE_SEC:
        return {
            "temperature_c": None,
            "humidity_pct": None,
        }

    temperature = (
        sample.temperature_c
        if sensor_temperature_range_ok(sample.temperature_c)
        else None
    )
    humidity = (
        sample.humidity_pct
        if 0 <= sample.humidity_pct <= 100
        else None
    )

    return {
        "temperature_c": temperature,
        "humidity_pct": humidity,
    }
```

온도 허용 범위는 센서 모델의 Datasheet 기준으로 설정한다. 센서가 장착되지 않았다면 `environment` Object 전체를 생략해도 된다.

#### 11. `mission`

```python
from time import monotonic

mission_state = None
mission_started_monotonic = None
mission_incident_count = 0

def start_mission():
    global mission_state
    global mission_started_monotonic
    global mission_incident_count

    mission_state = "RUNNING"
    mission_started_monotonic = monotonic()
    mission_incident_count = 0

def record_incident():
    global mission_incident_count
    mission_incident_count += 1

def build_mission():
    if mission_state is None:
        return None

    return {
        "state": mission_state,
        "elapsed_sec": int(monotonic() - mission_started_monotonic),
        "incident_count": mission_incident_count,
    }
```

- `state`: 명령 처리와 로컬 안전 전이에 따라 Mission 상태머신에서 결정
- `elapsed_sec`: 시스템 시각 변경의 영향을 받지 않도록 monotonic clock 사용
- `incident_count`: 프레임 수가 아니라 확정 발행한 Incident Event 수만 증가

#### 12. 최종 Payload 조립

```python
def build_telemetry():
    identity = new_message_identity()

    return {
        "schema_version": SCHEMA_VERSION,
        "message_id": identity["message_id"],
        "robot_id": ROBOT_ID,
        "boot_id": BOOT_ID,
        "seq": identity["seq"],
        "sampled_at": utc_now(),
        "mission_id": active_mission_id,
        "position": build_position(rmc, gga, gps_age_sec),
        "motion": build_motion(motor_command, robot_mode),
        "battery": build_battery(),
        "system": {
            "jetson_temp_c": read_jetson_temperature(),
        },
        "environment": build_environment(
            environment_sample,
            environment_sample_age_sec,
        ),
        "mission": build_mission(),
    }
```

발행 직전에 JSON Schema 검증을 수행하고, 필수 멤버 누락이나 숫자 범위 위반이 있으면 해당 표본은 발행하지 않고 로컬 오류 로그를 남긴다.

---

## 5. Event 공통 Envelope

Incident·Battery·Safety·Mission 사건에 공통으로 들어가는 식별자와 시각 구조다. `event_id`로 QoS 1 재전송 중복을 제거하고 `boot_id`와 `seq`로 사건 순서를 확인한다.

Incident·Battery·Safety·Mission Event는 다음 필드를 공통으로 사용한다.

```text
{
  "schema_version": "1.0",
  "event_id": "3cf98dc0-424a-4ef0-9423-57e53fcd72fc",
  "event_type": "EVENT_TYPE",           # 후보: 각 Event Topic에 정의된 event_type
  "robot_id": "RBT-001",
  "boot_id": "572b2e59-d31f-445f-a43d-7df80ba64203",
  "seq": 1482,
  "source_component": "ai",             # 후보: ai | power | safety | mission
  "occurred_at": "2026-07-26T05:15:34.231Z",
  "sent_at": "2026-07-26T05:15:35.002Z",
  "mission_id": "MIS-20260726-001"
}
```

```json
{
  "source_component": [
    "ai",
    "power",
    "safety",
    "mission"
  ]
}
```

- `event_id`는 QoS 1 재전송 중복을 제거하는 UUID다.
- `occurred_at`은 재전송해도 최초 발생 시각을 유지한다.
- `sent_at`은 재전송 시 갱신할 수 있다.
- 임무가 없으면 `mission_id`는 `null`이다.

---

## 6. Events — Incident

카메라와 AI가 점자블록 이상을 발견했을 때 **관측 사실과 위치·신뢰도·이미지 식별자**를 서버에 전달한다. 로봇은 탐지 사실만 보내며 접수·처리 중·완료 같은 행정 상태는 서버가 별도로 관리한다.

```text
{
  "topic": "tbs/v1/robots/RBT-001/events/incident",
  "direction": "ROBOT_TO_SERVER",
  "qos": 1,
  "retain": false,
  "payload": {
    "schema_version": "1.0",
    "event_id": "3cf98dc0-424a-4ef0-9423-57e53fcd72fc",
    "event_type": "BRAILLE_BLOCK_INCIDENT_DETECTED", # 후보: BRAILLE_BLOCK_INCIDENT_DETECTED
    "robot_id": "RBT-001",
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
      "accuracy_m": 3.8,
      "zone_id": "A-01"
    },
    "incident": {
      "asset_type": "BRAILLE_BLOCK",
      "block_type": "LINEAR",           # 후보: LINEAR | DOT | UNKNOWN
      "defect_type": "DAMAGED",         # MVP: DAMAGED
                                            # 예약: INSTALLATION_INVALID | UNKNOWN_ANOMALY
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
}
```

MVP:

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
    "INSTALLATION_INVALID",
    "UNKNOWN_ANOMALY"
  ]
}
```

이미지 업로드가 실패한 경우:

```json
{
  "evidence": {
    "image_id": null,
    "captured_at": "2026-07-26T05:15:34.120Z",
    "image_width": 1280,
    "image_height": 720,
    "bbox_norm": [
      0.16,
      0.42,
      0.81,
      0.95
    ]
  }
}
```

---

## 7. Events — Battery

배터리 퍼센트를 주기적으로 보내는 Topic이 아니라, `NORMAL → LOW`처럼 **배터리 상태가 바뀌는 순간**을 알리는 사건 Topic이다. 서버는 이를 이용해 저전력 경고와 임무 중단 사유를 기록한다.

```text
{
  "topic": "tbs/v1/robots/RBT-001/events/battery",
  "direction": "ROBOT_TO_SERVER",
  "qos": 1,
  "retain": false,
  "publish_rule": "배터리 상태 전이 시 1회",
  "payload": {
    "schema_version": "1.0",
    "event_id": "4d42ee50-f8e2-45dc-b348-2b5dd72f83bf",
    "event_type": "BATTERY_LOW",        # 후보: BATTERY_LOW | BATTERY_CRITICAL | BATTERY_RECOVERED
                                            #       BATTERY_SENSOR_FAULT | BATTERY_SENSOR_RECOVERED
    "robot_id": "RBT-001",
    "boot_id": "572b2e59-d31f-445f-a43d-7df80ba64203",
    "seq": 1488,
    "source_component": "power",
    "occurred_at": "2026-07-26T05:18:10.000Z",
    "sent_at": "2026-07-26T05:18:10.100Z",
    "mission_id": "MIS-20260726-001",
    "previous_state": "NORMAL",         # 후보: NORMAL | LOW | CRITICAL | RECOVERED | UNKNOWN
    "battery": {
      "valid": true,
      "percent": 19.8,
      "state": "LOW"                    # 후보: NORMAL | LOW | CRITICAL | RECOVERED | UNKNOWN
    },
    "threshold_pct": 20,
    "action_taken": {
      "mission_state": "PAUSED",
      "robot_mode": "SAFE_STOP"
    }
  }
}
```

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

---

## 8. Events — Safety

장애물·경계 소실·센서 고장 때문에 로봇이 정지하거나 안전 상태가 해제된 사실을 서버에 보고한다. 실제 긴급 정지는 로봇 내부에서 먼저 수행하고 이 Topic은 그 결과를 관제 서버에 알린다.

```text
{
  "topic": "tbs/v1/robots/RBT-001/events/safety",
  "direction": "ROBOT_TO_SERVER",
  "qos": 1,
  "retain": false,
  "publish_rule": "안전 상태 전이 시 1회",
  "payload": {
    "schema_version": "1.0",
    "event_id": "81fd9bd1-8639-47a2-82fd-9f26281dac68",
    "event_type": "SAFETY_STOPPED",     # 후보: SAFETY_STOPPED | SAFETY_CLEARED | SENSOR_FAULT | SENSOR_RECOVERED
    "robot_id": "RBT-001",
    "boot_id": "572b2e59-d31f-445f-a43d-7df80ba64203",
    "seq": 1491,
    "source_component": "safety",
    "occurred_at": "2026-07-26T05:19:02.410Z",
    "sent_at": "2026-07-26T05:19:02.450Z",
    "mission_id": "MIS-20260726-001",
    "reason_code": "OBSTACLE",          # 후보: OBSTACLE | BOUNDARY_LOST | REMOTE_ESTOP | LOW_BATTERY
                                            #       CRITICAL_BATTERY | LIDAR_FAULT | MOTOR_FAULT | INTERNAL_ERROR
    "measurement": {
      "distance_m": 0.72
    },
    "transition": {
      "mode_before": "PATROLLING",
      "mode_after": "SAFE_STOP"
    },
    "auto_resume_allowed": true
  }
}
```

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

> 실제 안전 정지는 MQTT가 아니라 로봇 내부 LiDAR 안전 프로세스가 수행한다.

---

## 9. Events — Mission

임무 시작·일시정지·재개·완료·취소·실패 같은 **임무 상태 전이**를 로봇이 서버에 보고한다. 완료 시 수행시간, 이동거리, 이상·정상 탐지 수 같은 최종 집계도 함께 전달한다.

```text
{
  "topic": "tbs/v1/robots/RBT-001/events/mission",
  "direction": "ROBOT_TO_SERVER",
  "qos": 1,
  "retain": false,
  "payload": {
    "schema_version": "1.0",
    "event_id": "2133612d-c882-446f-a283-72b2597f173e",
    "event_type": "MISSION_COMPLETED",  # 후보: MISSION_STARTED | MISSION_PAUSED | MISSION_RESUMED
                                            #       MISSION_COMPLETED | MISSION_CANCELLED | MISSION_FAILED
    "robot_id": "RBT-001",
    "boot_id": "572b2e59-d31f-445f-a43d-7df80ba64203",
    "seq": 2910,
    "source_component": "mission",
    "occurred_at": "2026-07-26T06:20:00.000Z",
    "sent_at": "2026-07-26T06:20:00.120Z",
    "mission_id": "MIS-20260726-001",
    "result": {
      "duration_sec": 4182,
      "distance_m": null,
      "incident_count": 7,
      "normal_detection_count": 143,
      "completion_reason": "ROUTE_COMPLETED"
    }
  }
}
```

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

- `result`는 완료·취소·실패처럼 최종 집계가 있는 Event에서 사용한다.
- 오도메트리가 없으면 `distance_m`은 GPS 누적 추정값 또는 `null`이다.

---

## 10. Commands — Request

관리자 요청을 검증한 백엔드가 로봇에 **실행할 제어 명령을 전달**하는 Topic이다. 임무 시작·정지·재개·취소·긴급정지를 보내며, 오래된 명령이 재실행되지 않도록 TTL과 Message Expiry를 적용한다.

```text
{
  "topic": "tbs/v1/robots/RBT-001/commands/request",
  "direction": "SERVER_TO_ROBOT",
  "qos": 1,
  "retain": false,
  "mqtt_5_message_expiry": 10,
  "payload": {
    "schema_version": "1.0",
    "command_id": "95626222-c5c8-47ea-901e-f9e467b275f6",
    "command_type": "EMERGENCY_STOP",   # MVP: START_MISSION | PAUSE_MISSION | RESUME_MISSION
                                            #      CANCEL_MISSION | EMERGENCY_STOP | CLEAR_EMERGENCY_STOP
                                            # 예약: RETURN_TO_CHARGE | UPDATE_CONFIG
    "issued_at": "2026-07-26T05:21:00.000Z",
    "expires_at": "2026-07-26T05:21:10.000Z",
    "requested_by": {
      "actor_type": "ADMIN",            # 후보: ADMIN | SYSTEM
      "actor_id": "ADM-0042"
    },
    "parameters": {
      "reason": "operator_request"
    }
  }
}
```

START_MISSION:

```json
{
  "schema_version": "1.0",
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

```json
{
  "mvp_command_type": {
    "START_MISSION": 60,
    "PAUSE_MISSION": 30,
    "RESUME_MISSION": 30,
    "CANCEL_MISSION": 30,
    "EMERGENCY_STOP": 10,
    "CLEAR_EMERGENCY_STOP": 30
  },
  "reserved_command_type": [
    "RETURN_TO_CHARGE",
    "UPDATE_CONFIG"
  ]
}
```

숫자는 권장 TTL 초다. `expires_at`과 MQTT 5 Message Expiry에 같은 만료시간을 적용한다.

---

## 11. Commands — Result

로봇이 Commands Request를 받은 뒤 **명령을 실제로 처리한 결과를 서버에 회신**하는 Topic이다. 서버와 관제 화면은 `command_id`를 기준으로 요청과 결과를 연결하여 성공·실패·거부·만료 상태를 표시한다.

```text
{
  "topic": "tbs/v1/robots/RBT-001/commands/result",
  "direction": "ROBOT_TO_SERVER",
  "qos": 1,
  "retain": false,
  "payload": {
    "schema_version": "1.0",
    "result_id": "e72802e1-bf90-4f79-aa2a-c98d347710c6",
    "command_id": "95626222-c5c8-47ea-901e-f9e467b275f6",
    "robot_id": "RBT-001",
    "status": "COMPLETED",              # 후보: ACCEPTED | COMPLETED | FAILED | REJECTED | EXPIRED
    "received_at": "2026-07-26T05:21:00.130Z",
    "completed_at": "2026-07-26T05:21:00.240Z",
    "reason_code": "OK",                # 예: OK | SAFETY_CONDITION_NOT_CLEARED | NO_ACTIVE_MISSION
                                            #     ALREADY_IN_STATE | INTERNAL_ERROR
    "state": {
      "mode": "SAFE_STOP",
      "reason_code": "REMOTE_ESTOP"
    }
  }
}
```

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

---

## 12. Ingest — Receipt

서버가 로봇 Event를 MQTT로 받은 것에서 끝나지 않고 **DB에 저장하거나 기존 결함에 병합한 결과까지 로봇에 확인해 주는** Topic이다. PUBACK보다 강한 애플리케이션 수준 저장 확인이며 MVP 이후 심화 기능이다.

```text
{
  "topic": "tbs/v1/robots/RBT-001/ingest/receipt",
  "direction": "SERVER_TO_ROBOT",
  "qos": 1,
  "retain": false,
  "scope": "DEEP",
  "payload": {
    "schema_version": "1.0",
    "event_id": "3cf98dc0-424a-4ef0-9423-57e53fcd72fc",
    "ingest_status": "STORED",          # 후보: STORED | MERGED | DUPLICATE | REJECTED
    "incident_id": "INC-20260726-000031",
    "received_at": "2026-07-26T05:15:35.150Z"
  }
}
```

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

MVP에서는 Receipt를 사용하지 않고 Event QoS 1 PUBACK를 기준으로 로봇 Outbox를 정리한다.

---

## 13. Topic 목록만 보기

Payload를 제외하고 로봇→서버와 서버→로봇 Topic 경로만 빠르게 확인하는 요약이다.

```json
{
  "robot_to_server": [
    "tbs/v1/robots/{robot_id}/presence",
    "tbs/v1/robots/{robot_id}/state",
    "tbs/v1/robots/{robot_id}/telemetry",
    "tbs/v1/robots/{robot_id}/events/incident",
    "tbs/v1/robots/{robot_id}/events/battery",
    "tbs/v1/robots/{robot_id}/events/safety",
    "tbs/v1/robots/{robot_id}/events/mission",
    "tbs/v1/robots/{robot_id}/commands/result"
  ],
  "server_to_robot": [
    "tbs/v1/robots/{robot_id}/commands/request",
    "tbs/v1/robots/{robot_id}/ingest/receipt"
  ]
}
```
