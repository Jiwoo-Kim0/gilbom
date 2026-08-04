# MQTT 송수신 동작 정리

> 이 문서는 **현재 저장소 코드가 실제로 하는 동작**을 적는다. 계약 문서가 아니라
> 구현 설명이다. 계약 정본은 `web/docs/final-mqtt-topic-schema-v2.md`(저장소 밖)이고,
> 서버 수신 규칙은 [`웹_통신계약_로봇_수신_정리.md`](웹_통신계약_로봇_수신_정리.md)에 있다.
>
> 작성 기준: 2026-08-01 · 계약 `tbs/v2` (`schema_version: "2.0"`)

---

## 0. 한눈에 보기

토픽은 `tbs/v2/robots/{mqtt_id}/...` 아래에 있다. `{mqtt_id}`는 `config.yaml`의
`robot.id`(= `MQTT_ID` 환경변수) 하나가 결정한다.

| 방향 | 토픽 | 언제 | QoS | Retain | Outbox |
| --- | --- | --- | :---: | :---: | :---: |
| 로봇→웹 | `presence` | 접속·종료·비정상 단절 | 1 | **O** | X |
| 로봇→웹 | `state` | 접속 직후 · 전이 즉시 · 30초 | 1 | **O** | X |
| 로봇→웹 | `telemetry` | 5초 주기 | **0** | X | X |
| 로봇→웹 | `events/incident` | 결함 확정 1건마다 | 1 | X | **O** |
| 로봇→웹 | `events/battery` | 배터리 등급 전이 | 1 | X | **O** |
| 로봇→웹 | `events/safety` | 장애물 확정·해제, 센서 고장·복구 | 1 | X | **O** |
| 로봇→웹 | `events/mission` | 미션 상태 전이 | 1 | X | **O** |
| 로봇→웹 | `commands/result` | 명령 접수·완료 | 1 | X | X |
| **웹→로봇** | `commands/request` | 관리자가 명령을 누를 때 | 1 | X | — |

로봇이 **구독하는 토픽은 `commands/request` 하나뿐**이다.
계약에 있는 `ingest/receipt`(웹→로봇)는 MVP 미사용이라 구독하지 않는다.

메인 루프는 **0.5초 tick**이다. 아래 "즉시"는 전부 "최대 0.5초 안에"라는 뜻이다.

```text
main.py 메인 루프 (0.5초)
  ├ commands.poll()    큐에 쌓인 명령을 전부 처리       → commands/result
  ├ publisher.poll()   전이 있으면 즉시, 없으면 30초    → state
  ├ sampler.poll()     5초 주기                        → telemetry
  ├ events.poll()      장애물·센서·배터리 판정          → events/safety, events/battery
  └ drain_detections() 추론 스레드가 확정한 관측 배분    → events/incident

별도 스레드
  ├ detect     카메라→YOLO 추론 (0.2초)     → 확정 관측을 큐에 넣음
  ├ lidar      시리얼 스캔 파싱
  ├ mqtt-outbox 미전송 event 재발행 (30초)
  └ paho       네트워크 루프 — 수신 명령을 큐에 넣기만 함
```

---

## 1. presence — 살아 있는지

`retain: true`라 브로커가 마지막 값을 들고 있고, 나중에 붙은 서버도 즉시 현재 상태를 본다.

### 1.1 ONLINE — 접속할 때마다

`comms/client.py:_on_connect`. **최초 접속뿐 아니라 재접속 때마다** 발행한다.
retain된 OFFLINE을 덮어써야 하기 때문이다.

```json
{
  "schema_version": "2.0",
  "mqtt_id": "RBT-001",
  "boot_id": "572b2e59-d31f-445f-a43d-7df80ba64203",
  "status": "ONLINE",
  "connected_at": "2026-08-01T06:15:35.000Z"
}
```

접속 직후 순서가 정해져 있다.

```text
presence ONLINE 발행 → commands/request 구독 → Outbox 플러시 깨움 → state 재발행
```

### 1.2 OFFLINE — 정상 종료

`comms/client.py:shutdown()`. SIGTERM(systemd stop)이나 Ctrl+C로 들어온다.
발행한 뒤 `disconnect()`를 부르므로 **브로커가 LWT를 버린다.**

```json
{ "...": "...", "status": "OFFLINE", "reason": "GRACEFUL_SHUTDOWN" }
```

### 1.3 OFFLINE — 비정상 단절 (LWT)

`start()`에서 `will_set()`으로 브로커에 미리 맡겨 둔다. 전원이 나가거나 LTE가
끊겨 DISCONNECT 없이 사라지면 **브로커가 대신 발행한다.**

```json
{ "...": "...", "status": "OFFLINE", "reason": "UNGRACEFUL_DISCONNECT" }
```

> **현재 안 쓰는 값:** enum에는 `CRITICAL_BATTERY_SHUTDOWN`,
> `SYSTEM_FAULT_SHUTDOWN`도 있지만 지금 코드는 발행하지 않는다.
> 배터리 임계로 인한 정지는 `SAFE_STOP`이지 프로세스 종료가 아니다.

`presence`에는 `seq`가 없다. 서버 freshness 검사 대상도 아니다(시각 기준 필드가 없음).

---

## 2. state — 지금 무슨 모드인지

`robot_state.py:StatePublisher`. **세 가지 조건 중 하나라도 맞으면** 발행한다.

| # | 조건 | 코드 |
| --- | --- | --- |
| (a) | 접속 직후 1회 (재접속마다) | `on_connected` → `publish(force=True)` |
| (b) | 전이가 있으면 즉시 | `publish_key`가 직전과 다르면 |
| (c) | 아무 일 없어도 30초마다 | `STATE_INTERVAL_SEC = 30.0` |

(b)의 **전이 판정 키**는 이 7개다.

```python
(mode, reason_code, mission_id, mission_state, battery_valid, battery_state, health)
```

> `battery.percent`는 **일부러 뺐다.** 소수점이 계속 흔들려서 넣으면 30초 주기가
> 무의미해진다. 계약이 요구하는 전이 신호는 `battery.state`(등급)다.

전이로 보내든 주기로 보내든 **타이머는 그때 다시 걸린다.** 같은 상태가 30초 안에
두 번 나가지 않는다.

`updated_at`은 **항상 단조 증가**한다. 서버는 기존 값보다 새로울 때만 갱신하는데
밀리초 3자리라, 같은 밀리초에 두 번 발행하면 뒤엣것이 통째로 버려진다. 직전 값과
같거나 이전이면 1ms 밀어서 순서를 지킨다.

```json
{
  "schema_version": "2.0",
  "mqtt_id": "RBT-001",
  "boot_id": "572b2e59-...",
  "seq": 1482,
  "updated_at": "2026-08-01T06:15:35.000Z",
  "mode": "PATROLLING",
  "reason_code": "NONE",
  "mission": { "mission_id": "MIS-20260801-001", "state": "RUNNING" },
  "battery": { "valid": true, "percent": 67.4, "state": "NORMAL" },
  "health": {
    "camera": "OK", "lidar": "OK", "gps": "UNKNOWN",
    "battery": "UNKNOWN", "motor": "OK", "storage": "OK"
  }
}
```

- 활성 미션이 없으면 `"mission": null`
- `health` 6종은 **항상 전부** 들어간다. 값은 `OK`/`DEGRADED`/`FAULT`/`UNKNOWN`
- `UNKNOWN`은 "검사 전 또는 판단 불가"다. 하드웨어가 안 붙은 GPS·배터리가 여기 해당한다
- 배터리를 못 믿으면 `{"valid": false, "percent": null, "state": "UNKNOWN"}`

### 현재 코드가 실제로 내는 mode·reason_code

계약 enum에는 더 있지만, 지금 코드가 만들어 내는 조합은 이것뿐이다.

| mode | 언제 | reason_code |
| --- | --- | --- |
| `BOOTING` | 프로세스 시작 직후 | `NONE` |
| `READY` | 초기화 완료 / 미션 종료 후 대기 | `NONE` |
| `PATROLLING` | START_MISSION·RESUME_MISSION | `NONE` |
| `PAUSED` | PAUSE_MISSION | `REMOTE_PAUSE` |
| `PAUSED` | 배터리 LOW 진입 (순찰 중) | `LOW_BATTERY` |
| `SAFE_STOP` | EMERGENCY_STOP | `REMOTE_ESTOP` |
| `SAFE_STOP` | 라이다 장애물 감지 | `OBSTACLE` |
| `SAFE_STOP` | 배터리 CRITICAL 진입 | `CRITICAL_BATTERY` |
| `SAFE_STOP` | LiDAR·모터 FAULT (순찰 중) | `LIDAR_FAULT` / `MOTOR_FAULT` |
| `SHUTTING_DOWN` | 종료 직전 마지막 state | `NONE` |

> **아직 안 내는 값:** `ERROR` mode, `BOUNDARY_LOST`·`INTERNAL_ERROR` reason_code.
> 계약 enum에는 있으나 이를 만들어 내는 경로가 아직 없다.

전이표는 `robot_state.py:_TRANSITIONS`가 강제한다. 예를 들어
`SAFE_STOP → PATROLLING`은 표에서 허용하지만, 명령 하나로 안전 정지를 지나칠 수
없도록 `START_MISSION`·`RESUME_MISSION`이 따로 막는다(§4.3).

---

## 3. telemetry — 5초마다 현재값

`telemetry.py:TelemetrySampler`. **주기 하나뿐이다.** 전이 트리거가 없다.

- QoS 0, retain false, **Outbox를 거치지 않는다** → 끊긴 동안의 표본은 그냥 버린다
- 재접속해도 **과거 표본을 몰아 보내지 않는다** (계약 §6.1)
- 발행이 실패해도 기한은 민다 — 실패한 표본을 다음 tick에 다시 보내면 그게 재전송이 된다
- 연속 3회 실패하면 그때 한 번만 `ERROR` 로그를 올린다(5초마다 찍으면 아무도 안 봄)

**배터리 센서를 읽는 유일한 지점이 여기다.** `state`와 `telemetry`가 각자 읽으면
같은 시점에 서로 다른 잔량을 보고하게 되므로, 읽기는 `RobotState`로만 들어가고
두 발행 경로는 스냅샷에서 읽는다. 그래서 **배터리 등급 판정도 5초 주기에 묶인다.**

```json
{
  "schema_version": "2.0",
  "message_id": "e7c1...",
  "mqtt_id": "RBT-001",
  "boot_id": "572b2e59-...",
  "seq": 1483,
  "sampled_at": "2026-08-01T06:15:40.000Z",
  "mission_id": "MIS-20260801-001",
  "position": { "valid": true, "lat": 37.5013, "lon": 127.0396, "heading_deg": 182.4 },
  "motion": { "moving": true, "speed_mps": null },
  "battery": { "valid": true, "percent": 67.4, "state": "NORMAL", "source": "MEASURED" },
  "system": { "jetson_temp_c": 48.5 },
  "mission": { "state": "RUNNING", "elapsed_sec": 412, "incident_count": 3 }
}
```

값이 만들어지는 규칙:

| 필드 | 규칙 |
| --- | --- |
| `position` | GPS Fix가 없으면 **넷 다 null**. 직전 좌표를 복사하지 않는다 — 끊긴 것과 그 자리에 선 것은 지도에서 완전히 다른 뜻이다 |
| `motion.moving` | 주행 PWM 명령 중이고 mode가 `PAUSED`·`SAFE_STOP`·`ERROR`가 **아닐 때** true |
| `motion.speed_mps` | **항상 null.** 엔코더가 없어 실제 속도를 잴 방법이 없다 |
| `system.jetson_temp_c` | `/sys/class/thermal/thermal_zone*/temp` 중 **최고값**. 못 읽으면 null |
| `mission` | 활성 미션이 없으면 `mission_id`와 `mission` 둘 다 null (**함께** 움직인다) |
| `mission.elapsed_sec` | 미션 시작부터의 **벽시계**. `PAUSED` 중에도 계속 증가한다 |

> `mission.elapsed_sec`이 서버 진행률 계산의 전제다.
> `progressPercent = min(99.99, elapsedSec / plannedDurationSec * 100)`
> 100%는 `MISSION_COMPLETED` event에서만 된다.

> **선택 확장 필드**(`voltage_v`, `current_a`, `charging`, `battery_temperature_c`,
> `estimated_runtime_sec`)는 `battery` 블록에 넣을 수 있게 열려 있지만
> **현재 아무것도 싣지 않는다.** 측정 회로가 없다.

---

## 4. events 4종

네 사건이 **공통 envelope**를 쓴다.

```json
{
  "schema_version": "2.0",
  "event_id": "3cf98dc0-424a-4ef0-9423-57e53fcd72fc",
  "event_type": "...",
  "mqtt_id": "RBT-001",
  "boot_id": "572b2e59-...",
  "seq": 1484,
  "source_component": "ai | power | safety | mission",
  "occurred_at": "2026-08-01T06:15:34.231Z",
  "sent_at": "2026-08-01T06:15:35.002Z",
  "ttl_sec": 300,
  "mission_id": "MIS-20260801-001"
}
```

- `occurred_at`은 **사건이 실제로 일어난 시각**, `sent_at`은 보낸 시각이다
- `ttl_sec`은 기본 300초. 서버는 `occurred_at + ttl_sec`이 지난 event를 거부한다
- 활성 미션이 없으면 `mission_id`는 null

**event 4종만 Outbox를 거친다.** 저장 → 발행 → PUBACK 받으면 `sent` 표시.
끊겨 있으면 저장만 하고 재접속 뒤 30초 주기 워커가 다시 보낸다. 재전송할 때
`event_id`·`occurred_at`·`ttl_sec`은 그대로 두고 **`sent_at`만 갱신**한다.

> ⚠️ 그래서 **5분 넘게 끊겨 있으면 밀린 event는 재전송해도 거부된다.** 신선도
> 기준이 계속 원래 `occurred_at`이기 때문이다. 계약대로 동작하는 것이지만
> 장시간 단절 후 결함이 실질적으로 유실된다는 뜻이라 운영상 알고 있어야 한다.

### 4.1 events/incident — 점자블록 결함

**흐름:**

```text
[detect 스레드 0.2초]
  카메라 프레임 → YOLO 추론 → 가장 확신하는 상자 1개
    → 같은 (block_type, defect_type)이 3프레임 연속이어야 확정
    → 확정 뒤 그 종류는 30초 쿨다운
    → 결함이면 증거 JPEG 저장 (장변 1280px, q80, 500KB 이하로 재인코딩)
    → 큐에 넣음
[메인 루프]
  drain_detections() → report_incident()
    → payload 조립 (event_id 생성)
    → 이미지 HTTPS 업로드 → image_id 받아서 채움
    → events/incident 발행
    → 미션 중이면 incident_count += 1
```

**정상 관측은 event가 아니다.** 미션 중일 때 `normal_detection_count`만 올라가고,
그 값은 미션 종료 event의 `result`에만 실린다.

```json
{
  "...envelope...": "source_component: ai",
  "location": { "valid": true, "lat": 37.5013, "lon": 127.0396, "zone_id": "A-01" },
  "incident": {
    "asset_type": "BRAILLE_BLOCK",
    "block_type": "LINEAR",
    "defect_type": "DAMAGED",
    "confidence": 0.87,
    "attributes": { "damage_ratio": 0.32 }
  },
  "evidence": {
    "image_id": "550e8400-e29b-41d4-a716-446655440000",
    "captured_at": "2026-08-01T06:15:34.100Z",
    "image_width": 1280,
    "image_height": 720,
    "bbox_norm": [0.16, 0.42, 0.81, 0.95]
  },
  "model": { "name": "tbs-braille-yolo", "version": "0.0.0-stub" }
}
```

| 필드 | 주의 |
| --- | --- |
| `location.zone_id` | `START_MISSION`이 준 값을 **그대로 echo**한다. 좌표로 계산하지 않는다 |
| `location.valid` | GPS Fix가 없으면 false + 좌표 null. **그래도 event는 나간다** |
| `evidence.image_id` | 업로드 실패하면 **null**. 사진이 없다고 결함을 삼키지 않는다 |
| `bbox_norm` | `[x_min, y_min, x_max, y_max]`, 각 0.0~1.0. **`x_min < x_max` 강한 부등식** |
| `attributes` | `damage_ratio`가 없으면 블록 자체를 생략한다 |
| `model.version` | ⚠️ 아직 `0.0.0-stub`. 실제 export 버전으로 교체 필요 |

> `zone_id`는 `patrol_area.area_code`를 해석하지 않고 그대로 전달한다. 서버와 로봇은
> 코드의 문자 구성에 의미를 부여하거나 별도 형식을 강제하지 않는다.

### 4.2 events/battery — 등급이 바뀌는 순간

`events.py:_poll_battery`. 매 tick 보지만 **등급이 바뀔 때만** 1회 발행한다.
등급 판정은 `RobotState`가 히스테리시스로 한다.

| 임계 | 값 | 복귀 |
| --- | --- | --- |
| LOW | 20% 미만 | 25% 이상에서 NORMAL |
| CRITICAL | 10% 미만 | 15% 이상에서 LOW |

`confirm_samples: 2`라 **2표본 연속**이어야 등급이 바뀐다. 배터리를 telemetry
주기(5초)에 읽으므로 실질 확인 시간은 **약 10초**다. 전압 기반 추정이라 모터가
물리면 퍼센트가 출렁이는데, 단일 임계값이면 LOW/RECOVERED가 번갈아 나간다.

| event_type | 언제 |
| --- | --- |
| `BATTERY_LOW` | NORMAL→LOW, 또는 기동 후 첫 판정이 LOW |
| `BATTERY_CRITICAL` | →CRITICAL |
| `BATTERY_RECOVERED` | 등급이 **올라갈 때** (CRITICAL→LOW 포함) |
| `BATTERY_SENSOR_FAULT` | 등급이 `UNKNOWN`이 됨 (측정을 못 믿게 됨) |
| `BATTERY_SENSOR_RECOVERED` | 센서 고장에서 다시 판정 가능해짐 |

**조치를 먼저 하고 그 결과를 `action_taken`에 담는다.**

- `CRITICAL` → `SAFE_STOP / CRITICAL_BATTERY`
- `LOW` + 순찰 중 → `PAUSED / LOW_BATTERY`

```json
{
  "...envelope...": "source_component: power",
  "previous_state": "NORMAL",
  "battery": { "valid": true, "percent": 19.2, "state": "LOW" },
  "threshold_pct": 20.0,
  "action_taken": { "mission_state": "PAUSED", "robot_mode": "PAUSED" }
}
```

> 미션 상태 전이 정책은 배터리 쪽에서 정하지 않는다. `action_taken.mission_state`에는
> 조치 후 **현재 값**이 그대로 실린다.

### 4.3 events/safety — 안전 정지·센서 고장

두 갈래가 독립적으로 돈다.

#### (1) 장애물 — 2단계 확정

**짧은 정지로 사건을 만들지 않기 위한 구조다.**

```text
t=0    라이다가 0.40m 안에서 뭔가 봄
       → 모터 먼저 정지 (MQTT보다 물리 정지가 먼저)
       → state: SAFE_STOP / OBSTACLE 로 전이      ← state 로만 알림, event 없음
t=3.0  obstacle_confirm_sec 경과 후 '다시 봤더니' 여전히 있음
       → 확정 → SAFETY_STOPPED 발행
       occurred_at = t=0 (실제로 선 시각). 확정 시각이 아니다.
```

3초 안에 치워지면 **event가 아예 안 나간다.** state로만 잠깐 보였다 사라진다.

해제도 대칭이다. 장애물이 사라지면 `SAFETY_CLEARED`를 내는데, **확정됐던 사건에
대해서만** 낸다. `SAFETY_STOPPED` 없이 `SAFETY_CLEARED`가 나가는 경로를 구조로 막았다.

확정 전에 다른 사유(관리자 e-stop 등)가 끼어들면 그 감지는 없던 것으로 하고
처음부터 다시 본다. 안 그러면 그 사유가 풀린 뒤 장애물이 그대로인데도 다시 못 선다.

#### (2) 센서 고장 — 상태가 바뀌는 모서리

`health.lidar` / `health.motor`가 `FAULT`로 **바뀔 때**와 **풀릴 때** 1회씩.

| event_type | reason_code | 부수 동작 |
| --- | --- | --- |
| `SENSOR_FAULT` | `LIDAR_FAULT` / `MOTOR_FAULT` | 순찰 중이면 모터 정지 + `SAFE_STOP` |
| `SENSOR_RECOVERED` | 같음 | 다른 고장이 없고 그 사유로 서 있었으면 정지 해제 |

> 서 있는 로봇까지 `SAFE_STOP`으로 밀지는 않는다. 장치가 하나도 없는 개발 PC가
> 부팅하자마자 안전 정지에 갇혀 `START_MISSION`이 통째로 막히는 것을 피하기 위해서다.
> (`config.yaml`의 `sensor_fault_stops_driving`로 끌 수 있다)

```json
{
  "...envelope...": "source_component: safety",
  "reason_code": "OBSTACLE",
  "measurement": { "distance_m": 0.32 },
  "transition": { "mode_before": "PATROLLING", "mode_after": "SAFE_STOP" },
  "auto_resume_allowed": true
}
```

`measurement.distance_m`은 라이다 거리다. 해당 없는 사건(센서 고장 등)이면 null.

### 4.4 events/mission — 미션 상태 전이

| event_type | 트리거 | `result` |
| --- | --- | --- |
| `MISSION_STARTED` | `START_MISSION` 명령 | 없음 |
| `MISSION_PAUSED` | `PAUSE_MISSION` 명령 | 없음 |
| `MISSION_RESUMED` | `RESUME_MISSION` 명령 | 없음 |
| `MISSION_CANCELLED` | `CANCEL_MISSION` 명령 | **있음** |
| `MISSION_COMPLETED` | `COMPLETE_MISSION` 명령 또는 자율 순찰 종료 | **있음** |
| `MISSION_FAILED` | ⚠️ **아직 호출자 없음** | **있음** |

> 관리자 정상 종료는 `COMPLETE_MISSION`, 수행 불가로 취소하는 경우는
> `CANCEL_MISSION`을 사용한다. 자율주행의 경로 완료도 `MISSION_COMPLETED`를
> 발행해야 한다.

종료 3종만 집계를 싣고, 실은 뒤 보관함을 비운다.

```json
{
  "...envelope...": "source_component: mission",
  "result": {
    "duration_sec": 1820,
    "incident_count": 7,
    "normal_detection_count": 143,
    "completion_reason": "OPERATOR_CANCELLED"
  }
}
```

- `duration_sec` — 미션 시작부터의 벽시계 (PAUSED 포함)
- `incident_count` — 발행한 결함 event 수
- `normal_detection_count` — 확정된 **정상** 관측 수 (event로는 안 나감)
- 기기는 총거리·진행률·남은 거리·ETA를 **보내지 않는다** (v2 결정)

---

## 5. commands/request — 웹이 로봇에 넣는 것

**로봇이 구독하는 유일한 토픽이다.**

### 5.1 웹에서 여기까지

```text
관리자가 버튼 클릭
  → POST /api/v1/robots/{robotId}/commands   (세션 + CSRF + Idempotency-Key)
  → 서버가 command_id(UUID)·issued_at·expires_at 생성
  → robot_command INSERT (CREATED)
  → MQTT 발행 → SENT, 실패하면 PUBLISH_FAILED (재시도 없음)
```

> 로봇이 명령을 못 받으면 **로봇 로그만 봐서는 알 수 없다.** 웹 DB의
> `robot_command.command_status`가 `PUBLISH_FAILED`인지부터 확인해야 한다.

### 5.2 받는 payload

```json
{
  "schema_version": "2.0",
  "command_id": "a75d9cec-b04d-4f66-b18a-4c48782f805a",
  "command_type": "START_MISSION",
  "issued_at": "2026-08-01T06:10:00.000Z",
  "expires_at": "2026-08-01T06:11:00.000Z",
  "requested_by": { "actor_type": "ADMIN", "actor_id": "ADM-0042" },
  "parameters": { "mission_id": "MIS-20260801-001", "zone_id": "A-01" }
}
```

`command_id`는 UUID지만 로봇은 **불투명 문자열로만** 다룬다(파싱하지 않는다).

### 5.3 명령 7종

| command_type | parameters | 서버 TTL | 로봇 동작 |
| --- | --- | ---: | --- |
| `START_MISSION` | `mission_id`, `zone_id` | 60s | `PATROLLING` 전이 → 미션 보관 → `MISSION_STARTED` |
| `PAUSE_MISSION` | `reason` | 30s | `PAUSED/REMOTE_PAUSE` → `MISSION_PAUSED` |
| `RESUME_MISSION` | 없음 | 30s | `PATROLLING/NONE` → `MISSION_RESUMED` |
| `COMPLETE_MISSION` | 없음 | 30s | 모터 정지 → `MISSION_COMPLETED`(집계 포함) → `READY` |
| `CANCEL_MISSION` | `reason` | 30s | `MISSION_CANCELLED`(집계 포함) → `READY` |
| `EMERGENCY_STOP` | `reason` | **10s** | **모터 정지 먼저** → `SAFE_STOP`; RUNNING Mission은 `PAUSED` + `MISSION_PAUSED` |
| `CLEAR_EMERGENCY_STOP` | `reason` | 30s | 원격 안전 정지만 해제; 활성 Mission은 `PAUSED`에서 명시적 재개 대기 |

긴급정지는 기존 RUNNING Mission을 먼저 `PAUSED`로 바꾼다. 따라서 원격 안전 정지를
해제해도 자동으로 주행하지 않으며, 별도 `RESUME_MISSION`을 받아야 순찰로 복귀한다.
장애물·센서 복구처럼 로컬 자동 재개가 허용된 경로는 `release_safe_stop`이 현재 Mission
상태에 따라 복귀 mode를 결정한다.

| 원격 긴급정지 해제 시 Mission | 복귀 |
| --- | --- |
| 활성 Mission | `PAUSED / REMOTE_PAUSE` |
| 미션 없음 | `READY / NONE` |

`RETURN_TO_CHARGE`·`UPDATE_CONFIG`는 서버 enum에 없고, 로봇도
`RESERVED_COMMAND_TYPES`로 두어 **와도 실행하지 않는다.**

`PAUSE`/`RESUME`/`CANCEL`의 `parameters`에는 `mission_id`가 **없다.** 서버가 내부적으로
활성 미션을 찾아 DB에는 기록하지만 payload에는 넣지 않는다. 로봇은 자기가 들고 있는
미션으로 처리한다.

### 5.4 수신 처리 순서

```text
[paho 네트워크 스레드]
  _on_message
    ├ 토픽이 commands/request 가 아님 → 무시 + warn
    ├ JSON 디코드 실패             → drop + payload 앞 120자 로그
    └ 큐에 넣기만 함                ← 여기서 해석하지 않는다

[메인 루프 매 tick]
  CommandRunner.poll()  ← 큐가 빌 때까지 전부 처리
```

> tick당 하나만 꺼내면 명령이 0.5초씩 밀린다. `EMERGENCY_STOP`은 그 지연을
> 허용하지 않으므로 큐를 한 번에 비운다.

검증 순서와 그때 나가는 결과 (`commands.py:handle`):

| # | 조건 | `commands/result` |
| --- | --- | --- |
| 1 | dict가 아님 | **없음** (로그만) |
| 2 | `schema_version != "2.0"` | **없음** — command_id조차 못 믿는다 |
| 3 | `command_id` 없음 | `REJECTED` / `INTERNAL_ERROR` |
| 4 | 최근 64개 안의 **중복** `command_id` | **없음** (조용히 무시) |
| 5 | 예약·미지원 `command_type` | `REJECTED` / `INTERNAL_ERROR` |
| 6 | `expires_at` 경과 | `EXPIRED` / `OK` |
| 7 | 필수 `parameters` 누락 | `REJECTED` / `INTERNAL_ERROR` |
| 8 | 실행 | `ACCEPTED` → `COMPLETED`\|`REJECTED`\|`FAILED` (**2건**) |

설계 포인트:

- **중복 판정을 검증보다 앞에 둔다.** 거부한 명령이 재전송돼도 조용해야 하기 때문이다
- `expires_at`이 없으면 만료를 **우리가 만들어내지 않는다**
- 검증에서 떨어진 명령도 결과는 낸다 — 서버가 무응답과 거부를 구분 못 하면 관제 화면이 접수 상태에서 영원히 멈춘다

> ⚠️ **중복 `command_id` 정책은 아직 미확정이다.** 현재 로봇은 아무것도 발행하지
> 않고 조용히 무시한다(재실행 방지). 반면 웹의 `mqtt-device-db-mapping.md`는
> "이전 결과를 다시 발행"을 요구한다. 무시하면 서버가 결과를 못 받아 timeout으로
> 처리할 수 있고, 재발행하면 로봇이 `command_id`별 결과 캐시를 들고 있어야 한다.
> 웹팀 확정 대기 중이며, 재발행으로 정해지면 이 동작을 바꿔야 한다.

> ⚠️ `command_id`가 없는 명령에 내는 `REJECTED`는 `command_id: null`이라 서버
> `@NotNull`에 걸려 거부된다. 서버가 모르는 명령이라 실해는 없지만, 웹 로그에
> `MQTT_PAYLOAD_VALIDATION_FAILED`로 남는다. 알고 둔 것이다.

### 5.5 실행이 거절되는 업무 조건

| reason_code | 언제 |
| --- | --- |
| `ALREADY_IN_STATE` | 이미 미션 중인데 `START_MISSION`, 이미 정지인데 `EMERGENCY_STOP` 등 |
| `NO_ACTIVE_MISSION` | 미션 없는데 `PAUSE`/`RESUME`/`CANCEL` |
| `SAFETY_CONDITION_NOT_CLEARED` | `SAFE_STOP`에서 `START_MISSION`/`RESUME_MISSION` |
| `SAFETY_CONDITION_NOT_CLEARED` | `CLEAR_EMERGENCY_STOP`인데 정지 사유가 `REMOTE_ESTOP`이 아님 |
| `INTERNAL_ERROR` | 핸들러가 예외를 던짐 (status는 `FAILED`) |

> 마지막 두 줄이 운영 중 제일 헷갈린다. **명령 하나로 안전 정지를 지나칠 수 없다.**
> 장애물(`OBSTACLE`)로 서 있는데 `CLEAR_EMERGENCY_STOP`을 눌러도 안 풀린다 —
> 그건 장애물이 치워지면 자동으로 풀린다.

---

## 6. commands/result — 명령 결과

```json
{
  "schema_version": "2.0",
  "result_id": "b1e4...",
  "command_id": "a75d9cec-...",
  "mqtt_id": "RBT-001",
  "status": "COMPLETED",
  "received_at": "2026-08-01T06:10:00.130Z",
  "completed_at": "2026-08-01T06:10:00.240Z",
  "reason_code": "OK",
  "state": { "mode": "PATROLLING", "reason_code": "NONE" }
}
```

- `boot_id`·`seq`가 **없다** (event·state와 다르다)
- `ACCEPTED`면 `completed_at`은 **반드시 null**, 그 외는 반드시 있고 `>= received_at`
- `state`는 **처리 후** 값이다 — 전이가 끝난 뒤에 읽는다
- `result_id`는 발행마다 새로 만든다. 같은 명령의 ACCEPTED와 COMPLETED는 서로 다른 `result_id`다

**정상 명령은 2건이 나간다.**

```text
ACCEPTED (completed_at: null)  →  COMPLETED (completed_at 있음)
```

**`EMERGENCY_STOP`만 순서가 다르다.** 모터를 세운 다음에 `ACCEPTED`를 낸다 —
소켓보다 바퀴가 먼저다.

```text
[모터 정지] → [SAFE_STOP 전이] → [필요 시 MISSION_PAUSED] → ACCEPTED → COMPLETED
```

검증에서 떨어진 명령은 **1건만** 나간다 (`REJECTED` 또는 `EXPIRED`).

---

## 7. 공통 규칙

### 7.1 boot_id · seq

- `boot_id` — 프로세스 시작 시 UUIDv4 **1회**. 재부팅하면 새 값
- `seq` — 한 `boot_id` 안에서 **`state`·`telemetry`·`events` 4종이 공유**하는 단조 증가 번호. 첫 값은 1
- `presence`와 `commands/result`에는 `seq`가 **없다**

서버는 이걸로 재전송·재부팅을 구분한다.

| 서버 규칙 | 위반 시 |
| --- | --- |
| 새 `boot_id`의 첫 메시지는 `seq == 1` | `MQTT_REPLAY_REJECTED` |
| 같은 boot 안에서 `seq` 단조 증가 | `MQTT_REPLAY_REJECTED` |
| `(mqtt_id, boot_id, seq)`를 다른 메시지가 사용 | `MQTT_SEQUENCE_CONFLICT` |
| 교체된 boot의 지연 메시지 | `MQTT_REPLAY_REJECTED` |

> ⚠️ **프로세스를 재시작하지 않고 `MessageFactory`만 새로 만들면 안 된다.**
> `boot_id`가 새 값인데 서버는 기존 boot를 `REPLACED_BY_NEW_BOOT`로 닫고
> `robot_current_state`를 전부 NULL로 초기화한다 (관제 화면이 잠깐 빈다).

### 7.2 시계

`expires_at` 판정이 **로봇 시계에 의존한다.** 시계가 밀리면 유효한 명령을 버리거나
만료된 명령을 실행한다. 서버의 freshness 검사도 마찬가지라 양방향으로 깨진다.

그래서 systemd unit이 `time-sync.target`에 의존한다 (`scripts/orincar.service`).

서버 신선도 기준:

| 토픽 | 기준 시각 | 최대 지연 |
| --- | --- | ---: |
| `telemetry` | `sampled_at` | 60s |
| `state` | `updated_at` | 300s |
| `commands/result` | `completed_at` ?? `received_at` | 60s |
| events 4종 | `occurred_at` | payload의 `ttl_sec` |
| 전부 | 미래 시각 허용치 | 5s |

### 7.3 로봇이 모르는 실패

서버가 거부한 메시지를 **로봇은 알 수 없다.** 브로커가 이미 PUBACK을 보냈으므로
Outbox는 `sent`로 지워진다. `ingest/receipt`가 MVP 미사용이라 이 구멍을 메울 채널이 없다.

거부는 웹 로그와 `gilbom.mqtt.inbound.rejected` 메트릭에만 남는다. 통합 기간에는
그 메트릭(태그 `topic`, `reason`)을 같이 봐야 한다.

로봇이 발행 전에 스스로 막는 것들(있으면 서버가 조용히 버릴 값):

- `bbox_norm`의 폭·높이가 0
- `battery.valid=true`인데 `state=UNKNOWN`
- `completed_at < received_at`
- `zone_id`는 형식 검증 없이 `START_MISSION` 값을 그대로 보존

### 7.4 재접속

- paho 자동 재접속, 1→2→4→…→30초 백오프
- `clean_start = FIRST_ONLY` + SessionExpiry 3600s → 재접속 시 **구독과 미수신 명령이 보존**된다
- 재접속하면 `presence ONLINE` → 구독 → Outbox 플러시 → `state` 재발행
- **`telemetry`는 재접속 훅이 없다.** 과거 표본을 보내지 않는다

### 7.5 절대 MQTT로 보내지 않는 것

JPEG 원본 · Base64 이미지 · 카메라 원시 프레임 · LiDAR 원시 스캔 · 조향각/모터 PWM ·
조이스틱 스트림. 이미지는 HTTPS로 올리고 **참조값(`image_id`)만** MQTT로 보낸다.

---

## 8. 미션 하나의 전체 흐름

```text
[기동]
  presence ONLINE (retain)
  commands/request 구독
  state  BOOTING/NONE  →  READY/NONE          ← 초기화 완료
  telemetry 5초마다 시작

[관리자가 순찰 시작]
  ← commands/request  START_MISSION {mission_id, zone_id}
  → commands/result   ACCEPTED
  state  PATROLLING/NONE                       ← 전이 즉시
  → events/mission    MISSION_STARTED
  → commands/result   COMPLETED
  telemetry 에 mission {RUNNING, elapsed_sec, incident_count} 붙기 시작

[결함 발견]
  (detect 스레드가 3프레임 연속 확정 + 30초 쿨다운)
  HTTPS POST /api/v1/images  → image_id
  → events/incident   BRAILLE_BLOCK_INCIDENT_DETECTED
  telemetry 의 incident_count 증가

[장애물]
  모터 정지 → state SAFE_STOP/OBSTACLE          ← event 없음
  3초 뒤에도 있으면 → events/safety SAFETY_STOPPED
  치워지면 → state PATROLLING/NONE + events/safety SAFETY_CLEARED

[배터리 20% 밑으로]
  → state PAUSED/LOW_BATTERY
  → events/battery BATTERY_LOW (action_taken: PAUSED)

[관리자가 종료]
  ← commands/request  CANCEL_MISSION {reason}
  → commands/result   ACCEPTED
  → events/mission    MISSION_CANCELLED + result{duration, incident_count, normal_count}
  state  READY/NONE
  → commands/result   COMPLETED
  telemetry 의 mission_id·mission 이 다시 null

[종료 — SIGTERM]
  모터 정지
  state  SHUTTING_DOWN/NONE                     ← 소켓에 실릴 때까지 최대 2초 대기
  presence OFFLINE / GRACEFUL_SHUTDOWN
  disconnect  → 브로커가 LWT 를 버린다
```

---

## 9. 관련 문서

- [웹 통신계약 로봇 수신 정리](웹_통신계약_로봇_수신_정리.md) — 서버 수신 규칙·거부 사유
- [임베디드 대조와 수정](MQTT-v2-임베디드-대조와-수정_구현내용.md) — 계약 대조 결과와 수정 내역
- [Jetson 주입·기동 절차](scripts/README.md)
- 코드: `comms/topics.py`(토픽·QoS) · `comms/factory.py`(payload 조립) ·
  `comms/client.py`(접속·발행·수신) · `robot_state.py`(state 타이밍) ·
  `telemetry.py` · `events.py` · `commands.py`
