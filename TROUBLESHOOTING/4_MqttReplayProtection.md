# MQTT 중복·역순·이전 Boot Replay 차단

## 1. 한 줄 요약

TTL은 오래된 메시지만 막을 뿐 유효시간 안의 중복과 순서 역전은 막지 못한다.
Robot별 `boot_id`와 State·Telemetry·Event 공용 `seq`를 DB Transaction 안에서
선점해 정확히 같은 재전송만 멱등 처리하고 나머지 Replay와 충돌을 거부한다.

## 2. 문제 현상

- QoS 1 재전송으로 같은 Telemetry나 Event가 두 번 저장된다.
- 늦게 도착한 낮은 `seq`가 최신 상태 뒤에 처리된다.
- 같은 `seq`에 다른 Payload 또는 다른 Message ID가 들어온다.
- Robot Process가 재시작됐는데 이전 Boot 메시지가 다시 도착한다.
- State와 Telemetry가 Topic별 독립 Sequence를 사용해 같은 Robot Boot에서
  순번이 충돌한다.
- 동시에 도착한 메시지가 각각 현재 순번을 읽고 둘 다 통과할 수 있다.

## 3. 원인

### 3.1 QoS 1은 중복 가능성이 있음

Publisher가 PUBACK을 받지 못하면 동일 메시지를 재전송할 수 있다. 최소 한 번
전달은 손실을 줄이지만 Consumer가 멱등하지 않으면 DB History와 후속 업무가
중복 실행된다.

### 3.2 TTL과 Replay는 다른 문제임

공격 또는 재전송 메시지가 TTL 안에 있으면 Freshness Guard를 통과한다. 최신성
검사만으로는 이미 처리한 Message인지, 순번이 뒤로 갔는지 판단할 수 없다.

### 3.3 단순 Current State 비교에는 경쟁 조건이 있음

두 Transaction이 같은 `last_seen_seq`를 동시에 읽고 각각 더 큰 순번이라고
판정하면 둘 다 처리될 수 있다. Sequence 확인과 갱신을 하나의 잠금 구간으로
묶어야 한다.

### 3.4 Robot 재시작 시 Sequence가 다시 1부터 시작함

`seq`만 전역 비교하면 정상 재시작도 Replay로 오인한다. 반대로 Boot 구분 없이
순번을 초기화하면 이전 Process의 지연 메시지가 새 Process 상태에 섞인다.

## 4. 원인에 따른 해결책

### 4.1 `boot_id`와 공용 `seq` 계약

- Robot 통신 Process 시작 시 새 UUIDv4 `boot_id`를 생성한다.
- State·Telemetry·Event가 하나의 증가 `seq`를 공유한다.
- 새 Boot의 최초 업무 메시지는 `seq=1`이어야 한다.
- Presence와 Boot Session으로 현재 Process 수명주기를 구분한다.

Topic별로 독립 Sequence를 발행하면 교차 Topic에서 같은 순번이 충돌하므로
Publisher도 반드시 공용 Counter를 사용해야 한다.

### 4.2 업무 처리 전에 Sequence Claim

각 수집 Service는 History INSERT나 Current State 변경 전에
`MqttReplayProtectionService.claim()`을 호출한다.

```text
Robot Current State 행 잠금
  → 동일 robot/boot/seq 기존 Claim 조회
  → message_id/event_id Identity 충돌 조회
  → Boot Session 상태와 순번 검증
  → 승인 순번 갱신
  → 업무 History INSERT와 Current State UPDATE
```

Claim과 업무 저장은 같은 Transaction에서 수행되므로 뒤 단계가 실패하면 승인
순번도 함께 Rollback된다.

### 4.3 정확히 같은 재전송만 멱등 처리

| 입력 | 처리 |
| --- | --- |
| 동일 Robot·Boot·Seq·Identity·Payload | `DUPLICATE`, 업무 재실행 없음 |
| 동일 Seq, 다른 Topic·Identity·Payload | `MQTT_SEQUENCE_CONFLICT` |
| 기존 Message ID/Event ID, 다른 좌표 | `MQTT_SEQUENCE_CONFLICT` |
| 현재보다 작거나 같은 미처리 Seq | `MQTT_REPLAY_REJECTED` |
| 새 Boot인데 최초 Seq가 1이 아님 | `MQTT_REPLAY_REJECTED` |
| 종료된 Boot | `MQTT_REPLAY_REJECTED` |
| 교체된 이전 Boot | `MQTT_REPLAY_REJECTED` |

### 4.4 Robot별 직렬화와 Robot 간 병렬성

같은 Robot의 메시지는 Current State 행 잠금과 Boot Session 잠금으로
직렬화한다. 서로 다른 Robot은 다른 행을 잠그므로 병렬 처리할 수 있다. 별도
Replay Table이나 Migration을 추가하지 않고 기존 Boot Session, Current State,
History Unique 제약을 사용한다.

### 4.5 거부 Metric

TTL과 같은 `gilbom.mqtt.inbound.rejected` Counter를 사용하고 `topic`,
`reason`을 제한된 값으로 기록한다. UUID나 Payload를 Tag에 넣지 않는다.

## 5. 해결 결과

- Replay 집중 검증: 4 Suite, 30 Test 통과
- 전체 Backend: 98 Suite, 514 Test 통과
- 동시성 회귀 Test 통과
- 동일 재전송은 멱등 처리
- 교차 Topic 충돌, 비증가 순번, 이전 Boot, 종료 Boot 차단
- 새 Boot 전환 시 이전 Boot의 Current 측정값을 비워 상태 혼합 방지

2026-07-30에 Mock Robot Publisher→실제 TLS Mosquitto→Spring Parser·Handler→
PostgreSQL 경로를 추가 검증했다. 같은 Mission Event Payload를 두 번 발행해
`robot_event`와 `mission_status_history`가 각각 1건만 저장되는 것을 확인했다.
로컬 Broker 결과와 해결 과정은
`TROUBLESHOOTING/8_MqttInboundEventBrokerE2E.md`에 기록했다. 실제 Jetson과 EC2
Broker의 재전송 검증은 Day 7 후속 범위다.

## 6. 적용 전후 결과를 표현하는 방법

이 기능은 “몇 ms 빨라졌다”보다 무결성 결과가 핵심이다.

| 동일 입력 시나리오 | Guard 없음 | Guard 적용 후 |
| --- | --- | --- |
| 동일 Telemetry 10회 재전송 | 중복 INSERT 가능 | 최초 1건만 업무 반영 |
| 같은 Seq, 다른 Payload | 마지막 값으로 오염 가능 | 충돌 거부 |
| 낮은 Seq 역순 도착 | 과거 상태 재처리 가능 | Replay 거부 |
| 이전 Boot 메시지 | 새 Boot와 상태 혼합 가능 | 이전 Boot 거부 |

성능 비용도 확인하려면 Guard 활성·비활성 각각에서 Robot 1·10·50대의 정상
Telemetry 처리량과 p95 DB Transaction 시간을 비교한다. 같은 Robot에 초고빈도
메시지를 집중시키면 행 잠금 대기가 증가할 수 있으므로, 전체 처리량뿐 아니라
Robot별 Lock 대기도 확인해야 한다. 아직 측정값은 없으므로 결과를 추정해 쓰지
않는다.

## 7. 장애 확인 순서

1. Publisher가 Process 시작마다 새 UUIDv4 `boot_id`를 만드는지 확인한다.
2. State·Telemetry·Event가 공용 `seq`를 사용하는지 확인한다.
3. 새 Boot 최초 업무 메시지가 `seq=1`인지 확인한다.
4. `robot_boot_session`의 상태와 `last_seen_seq`를 확인한다.
5. Current State의 현재 Boot와 승인 Sequence를 확인한다.
6. History의 동일 Robot·Boot·Seq Claim을 확인한다.
7. `gilbom.mqtt.inbound.rejected`의 `reason`을 확인한다.
8. 중복을 재시도해야 한다면 Payload와 Message ID까지 정확히 동일한지 확인한다.

## 8. 주의사항

- `last_seen_seq`는 Topic별 최댓값이 아니라 세 업무 Topic에서 마지막 승인된 공용
  순번이다.
- 정확한 중복은 정상적인 QoS 1 재전송이므로 오류로 다시 업무 처리하지 않는다.
- 같은 Event ID에 다른 Payload가 들어오는 것은 수정이 아니라 계약 위반이다.
- 새 Boot 전환 시 이전 측정값 제거는 서로 다른 Process의 상태 혼합을 막기 위한
  의도된 동작이다.
- TTL Guard를 Replay Guard로 대체하거나 반대로 Replay Guard만 두면 안 된다.

## 9. 관련 파일

- `web/backend/src/main/java/com/gilbom/robot/mqtt/replay/MqttReplayProtectionService.java`
- `web/backend/src/main/java/com/gilbom/robot/telemetry/TelemetryIngestionService.java`
- `web/backend/src/main/java/com/gilbom/robot/status/RobotStatusIngestionService.java`
- `web/backend/src/test/java/com/gilbom/robot/integration/TelemetryIngestionIntegrationTest.java`
- `web/backend/src/test/java/com/gilbom/robot/mqtt/client/MqttInboundBrokerE2E.java`
- `web/docs/final-mqtt-topic-schema-v2.md`
- `TROUBLESHOOTING/8_MqttInboundEventBrokerE2E.md`
- `CHANGLOGS/2026-07-29_1527_mqtt-replay-protection.md`
