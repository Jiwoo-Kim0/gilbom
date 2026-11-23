# 「미션 완료 및 서버·로봇 동기화 계약」 로봇 측 검토 결과

> 검토 대상: 목표 설계안 (2026-08-05)
> 검토 기준: AGS1/Jetson Robot 실제 코드 (`communication/`, `comms/`)
> 검토일: 2026-08-05
> 결론: 방향은 타당함. 구현 착수 전 **반드시 반영 5건**, **보완 권고 5건**.

파일:라인 표기는 모두 로봇 저장소 기준이다.

## 1. 검토 결론 요약

설계안의 책임 분리(서버가 완료 시점 결정, 로봇이 실제 종료 확인, 이벤트 저장 후
확정)는 현재 로봇 구현과 충돌하지 않는다. 특히 **오프라인 시 명령을 만들지 않고
재연결을 기다린다**는 §4 결정은 현재 로봇 동작에서 실제로 발생하는 유실 경로
(만료 명령이 `EXPIRED`로 끝나 `MISSION_COMPLETED`가 나가지 않음)를 정확히 막는다.

다만 아래 1번 항목은 설계안대로 구현할 경우 **정상 진행 중인 미션이 사람 개입을
요구하는 상태로 빠지는** 경로가 생긴다. 나머지 4건은 배포 중 조용한 payload 유실
또는 무기한 대기 상태를 만든다.

## 2. §13 "현재 구현과의 차이" 검증

설계안 §13에 적힌 7개 항목은 **전부 로봇 코드와 일치**한다. 별도 정정 없음.

| §13 항목 | 검증 근거 |
| --- | --- |
| COMPLETE 파라미터가 빈 객체 | `communication/commands.py:37` — `"COMPLETE_MISSION": ()` |
| 자동·수동 모두 `OPERATOR_COMPLETED` 발행 | `communication/commands.py:394` |
| Mission Event에 `causation_command_id` 없음 | `comms/factory.py:409-417` 시그니처에 부재 |
| Robot 설정이 `receipt_required: false` | `comms.yaml:18` |
| COMPLETE `ACCEPTED`가 `IDLE` 적용 후 지연 발행 | `communication/commands.py:43`, `189-192` |
| Backend 자동 `ingest/receipt` 발행 없음 | Backend 소관 — 로봇 측 수신 경로는 구현되어 있음 |
| 자동 완료 SQL에 Online·freshness 조건 없음 | Backend 소관 |

§2.1의 "진행률은 로봇의 `mission.elapsed_sec`로 계산"도 맞다. telemetry에 실제로
존재한다 — `comms/factory.py:166`, `202-213`.

`progress_percent`를 로봇이 보내지 않는다는 전제도 코드와 일치한다
(`comms/factory.py:419` — "기기는 총거리·진행률·남은 거리·ETA 를 보내지 않는다 (v2 결정)",
`tests/test_factory.py:346-348`, `670`이 부재를 검증).

## 3. 반드시 반영 (5건)

### 3.1 [P0] 재연결 시 새 완료 명령이 로봇에 거부된다

`communication/commands.py:240-242`는 **대기 중인 명령이 하나라도 있으면**
`EMERGENCY_STOP`과 `CANCEL_MISSION`을 제외한 모든 명령을 `ALREADY_IN_STATE`로
거부한다.

설계안 §7의 "재연결, 동일 Mission `RUNNING` → 새 TTL·새 command_id로 완료 명령
생성"을 그대로 구현하면, 직전 COMPLETE가 아직 pending인 상태(로봇이 `IDLE`에
도달하지 못해 대기 중)에서 새 명령이 `REJECTED`된다. 그러면 §7의 "`REJECTED`는
무한 자동 재시도 금지 → 확인 필요 상태"에 걸려 **정상 진행 중이던 미션이 관리자
개입을 요구하는 상태로 빠진다.**

조치 — §4의 마지막 조건을 다음과 같이 구체화한다.

```text
AND 대상 mission_id에 대한 직전 COMPLETE_MISSION 명령이
    terminal result(COMPLETED·FAILED·REJECTED·EXPIRED)를 받았거나
    expires_at을 경과했음
```

서버가 result를 수신하지 못한 채 TTL만 보고 재발행하면 이 경로에 그대로 걸린다.

### 3.2 [P0] §6 reason_code 3개는 현재 계약에 없는 신규 값이다

설계안이 도입하는 `MISSION_MISMATCH`, `COMMAND_TTL_EXPIRED`,
`STOP_APPLICATION_FAILED`는 현재 계약에 없다. 로봇의 결과 코드 상수는 5개뿐이고
(`communication/commands.py:58-62`), 만료는 `reason_code: "OK"` + `status: "EXPIRED"`로
나간다 (`communication/commands.py:227`).

로봇 측 `command_result` 빌더는 `reason_code`를 enum 검증하지 않으므로
(`comms/factory.py:484` — 값을 그대로 통과시킴) 로봇은 새 값을 즉시 실어 보낼 수
있다. 위험은 반대편이다. **Backend DTO가 enum이면 `MQTT_PAYLOAD_VALIDATION_FAILED`로
조용히 버려지고, 관제 화면의 명령이 영원히 `ACCEPTED`에 멈춘다.** 같은 종류의 사고에
대한 방어 주석이 이미 코드에 있다 (`comms/factory.py:469-472`).

"활성 Mission 없음"을 `REJECTED` → `FAILED`로 바꾸는 것도 Backend의 status 분기에
영향을 준다.

조치 — §12 배포 순서 **1단계에 다음을 추가**한다. 현재 1단계는 Mission Event의
선택 필드만 다룬다.

```text
1. Backend가 기존·신규 Mission Event를 모두 수신하도록 선택 필드를 먼저 허용한다.
   + Backend가 신규 command result reason_code와 status 조합을 먼저 허용한다.
```

### 3.3 [P0] §10의 재발행 순서가 현재 계약 순서와 다르다

설계안 §10은 "재연결 직후 Presence, State, Telemetry 순서로 최신 상태 발행"이라고
적었으나, 실제 계약 순서는 다음과 같다 (`comms/client.py:310-345`).

```text
presence → 구독 → Outbox 밀린 Event → state → (메인 루프) telemetry
```

Outbox에 쌓인 Event가 새 state·telemetry보다 **먼저** 나가야 하는 이유는 seq
단조성이다. 서버는 새 boot의 첫 메시지가 `seq == 1`이 아니거나 이미 본 seq 이하이면
조용히 버리는데(§3.5), 끊긴 동안 쌓인 Event는 더 낮은 seq를 들고 있다.

체크리스트에 State·Telemetry만 적으면 이 제약이 문서에서 사라지고, 그 결과 §7의
"Event 유실 후 Outbox 재전송"이 서버에서 조용히 폐기된다.

조치 — §10 해당 항목을 다음으로 교체한다.

```text
- [ ] 재연결 직후 Presence → 구독 → Outbox Event → State → Telemetry 순서로 발행
      (Outbox Event가 새 State·Telemetry보다 낮은 seq를 갖기 때문)
```

### 3.4 [P1] PAUSED로 계획 시간을 넘긴 미션이 무기한 남는다

§4는 `robot_current_state.mission_status = RUNNING`을 요구하고, 로봇도 미션이
`RUNNING`이 아니면 `ALREADY_IN_STATE`로 거부한다
(`communication/commands.py:298-299`). 원격 `EMERGENCY_STOP`을 받으면 RUNNING
미션은 `PAUSED`로 전이되고(`communication/commands.py:402-403`), §7의 `SAFE_STOP`
행은 "해제·재개 후 다시 평가"로만 되어 있다.

사람이 재개를 누르지 않으면 그 재평가는 발생하지 않는다. 계획 시간을 한참 넘긴
미션이 `PAUSED`로 무기한 남는다.

조치 — §7 표에 다음 행을 추가하고 종결 정책을 확정한다.

| 상황 | 서버 처리 | 로봇 처리 | 최종 상태 |
| --- | --- | --- | --- |
| `PAUSED` 상태로 계획 시간 + N분 초과 | 자동 완료 금지, 확인 필요 상태 승격 | 현재 상태 유지 | 관리자 조정 또는 정책상 `CANCELLED` |

### 3.5 [P1] §8이 현재 재기동 동작과 충돌한다 — 정책 결정 필요

§8은 "로봇 재부팅 후 로컬 Mission 상태를 복원할 수 없다면 서버의 `RUNNING` Mission을
임의로 `COMPLETED`로 바꾸지 않는다"고만 규정한다. **복원에 성공한 경우**는 다루지
않는다.

현재 로봇은 재기동 시 고아 미션을 보관함에 되살린 뒤 **곧바로 `MISSION_FAILED`를
발행해 서버 미션을 닫는다** (`communication/robot_state.py:609-616`,
`take_orphan_mission`). 즉 현재 동작은 다음과 같다.

| 재기동 후 | 현재 동작 |
| --- | --- |
| 로컬 Mission 복원 성공 | 미션을 되살린 뒤 즉시 `MISSION_FAILED` 발행 (주행은 재개하지 않음) |
| 로컬 Mission 복원 실패 | 아무 Event도 내지 않음 (`mission = null` 보고) |

§8이 요구하는 신중함은 이 두 동작 중 **후자**에만 대응한다. 전자를 유지할지
`ROBOT_MISSION_STATE_LOST` 계열의 확인 필요 상태로 바꿀지 문서에 명시해야 한다.
Backend가 §8을 그대로 구현하면서 로봇이 기존 동작을 유지하면, 서버는 확인 필요로
잡아 두려는 미션을 로봇이 `FAILED`로 닫아 버린다.

## 4. 보완 권고 (5건)

### 4.1 `command_id` 중복 방지 기록이 재부팅을 넘지 못한다

§8과 §10이 요구하는 "처리 중·처리 완료 `command_id` 중복 방지 기록"은 현재 메모리
전용이다 — `OrderedDict` 64개 (`communication/commands.py:30`, `103`, `504-506`).
`mission.json`에도 없다. 저장 필드는 6개뿐이다
(`communication/robot_state.py:583-587`).

```json
{"mission_id": ..., "mission_state": ..., "zone_id": ...,
 "started_at": ..., "incident_count": ..., "normal_detection_count": ...}
```

MQTT 세션은 `clean_start = MQTT_CLEAN_START_FIRST_ONLY`로 유지되므로
(`comms/client.py:145`) 재부팅 후 재배달된 명령이 다시 실행될 수 있고, §4의 서버측
가드는 **이미 발행된** 메시지를 막지 못한다. §12 배포 순서에서 이 항목이 몇 단계인지
지정해야 한다 (§10의 신규 작업 중 실질적으로 유일하게 새로 만들어야 하는 영속 상태다).

### 4.2 §7의 "Outbox Event 재전송" 행은 6단계 이후에만 성립한다

`receipt_required = false`인 동안에는 PUBACK 시점에 Outbox 행이 성공으로 닫힌다
(`comms/outbox.py:103-104` — `terminal_column`이 `sent_at`). §12의 2~5단계 구간에서는
§7의 "Command 완료, Event 미수신 → Outbox Event 재전송" 행이 동작하지 않는다는 점을
§12에 명시하는 편이 좋다.

Receipt 상태별 처리는 이미 설계안과 동일하게 구현되어 있다 —
`STORED`·`MERGED`·`DUPLICATE`만 성공으로 간주하고 `REJECTED`는 보존한다
(`comms/outbox.py:37-38`).

### 4.3 진행률 계산 기준을 하나로 고정할 것

§2.1은 "로봇의 `mission.elapsed_sec`", §4는 "서버 `started_at` + `planned_duration_sec`"를
기준으로 쓴다. 로봇은 재기동 시 monotonic 시계를 벽시계 기준으로 되맞추므로
(`communication/robot_state.py:578-587`) 두 값이 어긋날 수 있고, 그 경우 "진행률
92%인데 완료 명령이 나가는" 화면이 나온다. 표시용과 판단용 중 어느 쪽을 정본으로
할지 §2.1에 한 줄 명시하는 것을 권한다.

### 4.4 §5.1 `parameters.mission_id` 추가는 현재 로봇을 깨뜨리지 않는다

현재 로봇은 `REQUIRED_PARAMETERS`에 정의된 필수 키만 검사하므로
(`communication/commands.py:229-234`) 추가 키가 있어도 그대로 통과한다. 따라서 §12의
3단계 이전에 5단계를 수행해도 즉시 장애가 나지는 않는다.

다만 그 구간에서는 로봇이 `mission_id`를 보지 않고 실행하므로 §6-2의 미스매치 가드가
없는 채로 운영된다. §12의 순서(3단계 → 5단계)는 그대로 지키는 것이 맞다.

### 4.5 §5.2 Event payload는 필드 하나만 추가하면 된다

현재 event envelope은 `schema_version`, `event_id`, `mqtt_id`, `boot_id`, `seq`,
`source_component`, `occurred_at`, `sent_at`, `ttl_sec`, `mission_id`를 이미 싣는다.
`result` 블록 4개 필드도 설계안 예시와 동일하다 (`comms/factory.py:426-448`).
따라서 로봇 측 신규 필드는 `causation_command_id` 하나다.

참고로 종료 Event는 `result` 블록 전체가 필수다 (`comms/factory.py:429-430`). 집계가
없는 경로(`mission_cancelled_for`)는 0으로 채워 발행한다. `causation_command_id`를
필수로 강화할 때(§12 7단계) 이 경로에 `null` 허용이 필요하다.

## 5. 문서 수정 요청 목록

| 위치 | 요청 |
| --- | --- |
| §4 마지막 조건 | 직전 명령의 terminal result 또는 TTL 경과를 조건에 명시 (3.1) |
| §7 | `PAUSED` + 계획 시간 초과 행 추가 (3.4) |
| §8 | 로컬 Mission **복원 성공** 시의 재기동 정책 명시 (3.5) |
| §10 | 재연결 발행 순서에 Outbox Event 위치 포함 (3.3) |
| §12 1단계 | 신규 reason_code·status 선허용 추가 (3.2) |
| §12 | `command_id` 영속화 단계 지정 (4.1), 2~5단계의 Outbox 재전송 미동작 명시 (4.2) |
| §2.1 | 진행률 정본 기준 명시 (4.3) |

## 6. 검토 방법

로봇 저장소의 다음 파일을 직접 읽어 대조했다. Backend 측 주장(자동 완료 SQL 조건,
Receipt 발행 여부)은 이 저장소 범위 밖이므로 검증하지 않았다.

- `communication/commands.py` — 명령 검증·전제조건·적용 판정·result 발행
- `communication/events.py` — Mission Event 발행
- `communication/robot_state.py` — Mission 영속화·고아 미션 복구
- `comms/factory.py` — payload 조립과 필드 검증
- `comms/contract.py` — enum 정의
- `comms/client.py` — 접속 절차·세션·구독
- `comms/outbox.py` — Receipt 상태 처리
- `comms.yaml`, `docs/INGEST-RECEIPT.md`
