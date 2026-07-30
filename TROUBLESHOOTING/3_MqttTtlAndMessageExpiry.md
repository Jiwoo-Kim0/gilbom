# 오래된 MQTT 데이터와 만료 명령의 실행 차단

## 1. 한 줄 요약

Payload의 `expires_at`만 저장해서는 오프라인 Queue에 남은 명령의 전달을 막을 수
없다. 수신 전 Freshness Guard와 MQTT 5 Message Expiry를 함께 적용해 오래된
데이터의 DB 반영과 만료 명령의 Broker 재전달을 각각 차단했다.

## 2. 문제 현상

- LTE 단절 중 Broker에 쌓인 명령이 Robot 재연결 후 뒤늦게 전달된다.
- 이미 만료된 `START_MISSION`이나 `EMERGENCY_STOP`이 현재 상태에서 실행될 수 있다.
- 오래된 Telemetry가 History에 저장되거나 Current State 판단에 영향을 줄 수 있다.
- 지연된 Command Result가 이미 만료된 Command 상태를 다시 변경할 수 있다.
- Robot 시계가 서버보다 크게 앞서 있으면 미래 데이터가 최신 상태처럼 보일 수 있다.

## 3. 원인

### 3.1 QoS 1은 최신성을 보장하지 않음

QoS 1의 의미는 최소 한 번 전달이다. 메시지가 업무적으로 아직 유효한지, 이미
오래된 명령인지 판단하지 않는다. Persistent Session을 사용하는 Robot이
오프라인이면 Broker는 QoS 1 Command를 Queue에 보관할 수 있다.

### 3.2 Payload 만료시각과 Broker Queue 만료는 별개

Command JSON에 `expires_at`이 있어도 Broker는 JSON 내부를 해석하지 않는다.
Publisher가 MQTT 5 `message-expiry-interval` 속성을 설정하지 않으면 Broker는
만료시각을 모른 채 재연결 시 Command를 전달할 수 있다.

### 3.3 수신 Topic마다 최신성 기준이 다름

Telemetry는 촘촘한 현재값이므로 짧은 허용 지연이 필요하지만, State는 터널이나
엘리베이터 통신 단절 뒤 복구 가능성을 고려해 더 긴 지연을 허용해야 한다.
Event는 사건별 유효시간이 다르고, Command Result는 원 Command의 만료시각도 함께
확인해야 한다.

## 4. 원인에 따른 해결책

### 4.1 Handler와 DB 쓰기 전에 Freshness 검증

`MqttInboundFreshnessGuard`를 Payload 검증 직후, Dispatcher·업무 Handler 전에
실행한다. 거부된 메시지는 History INSERT나 Current State UPDATE를 발생시키지
않는다.

| Topic | 기준 시각 | 최대 지연 |
| --- | --- | ---: |
| Telemetry | `sampled_at` | 60초 |
| State | `updated_at` | 300초 |
| Event | `occurred_at` | Payload `ttl_sec`, 60~300초 |
| Command Result | `completed_at`, 없으면 `received_at` | 60초 |

서버 `receivedAt - 기준시각`이 최대 지연과 같거나 크면 만료다. 기준시각이 서버
수신시각보다 5초를 초과해 미래이면 `MQTT_TIMESTAMP_IN_FUTURE`로 거부한다.

Command Result는 위 지연 검증과 별개로 원 Command의 정확한 `expires_at`보다
서버 수신시각이 앞선 경우에만 허용한다.

### 4.2 Broker에도 같은 만료시간 전달

Backend Publisher는 Command의 남은 TTL을 MQTT 5 Message Expiry로 전달한다.
따라서 방어 위치가 둘로 나뉜다.

```text
Backend Command 생성
  → expires_at 저장
  → MQTT 5 message-expiry 설정
  → Broker Queue에서 만료 메시지 폐기
  → Robot이 메시지를 받더라도 Payload expires_at 재검증
```

Command는 QoS 1, Retain `false`를 사용한다. Retained Command가 새 구독자에게
전달되어 재실행되는 경로를 만들지 않는다.

### 4.3 거부 원인을 제한된 Metric으로 기록

거부 시 `gilbom.mqtt.inbound.rejected` Counter를 증가시킨다.

- `topic`: 고정 Topic Type
- `reason`: `expired`, `future_timestamp` 등 고정 값

Robot UUID, Message ID, Payload 원문을 Tag로 사용하지 않아 Metric Cardinality
폭증을 막는다.

## 5. 해결 결과

### Backend 검증

- Parser 단위: 11 Test 통과
- Parser·Command·Event 집중: 27 Test 통과
- Command·Dispatcher 집중: 14 Test 통과
- 당시 전체 Backend: 98 Suite, 508 Test 통과
- 만료 메시지가 Handler와 DB에 부작용을 만들지 않는 것 확인

### 실제 TLS Broker E2E

동일한 Persistent Session에 TTL이 다른 두 Command를 발행하고 Robot을 4초 뒤
재연결했다.

| Command | 설정 | 결과 |
| --- | --- | --- |
| 짧은 Command | TTL 2초 | Broker Queue에서 만료되어 미전달 |
| 긴 Command | TTL 30초 | 재연결 후 1건 전달 |

추가로 재연결 시 `sessionPresent=true`, QoS 1, Retain `false`, 긴 Command의
남은 Message Expiry가 24~27초인 것을 확인했다.

2026-07-30에는 Robot Event 수신 경로도 실제 TLS Broker로 확장했다. 만료 Mission
Event와 미래 Incident Event가 Handler·DB 전에 거부되고, 동일 Mission Event
재전송이 Event·Mission History를 각각 1건만 만드는 것을 확인했다. E2E 구성 중
발견한 Embedded `mqtt_id`·`ttl_sec` 누락과 Test Publisher 직렬화 문제는
`TROUBLESHOOTING/8_MqttInboundEventBrokerE2E.md`에 기록했다.

## 6. 적용 전후 결과를 표현하는 방법

이 작업의 핵심 결과는 처리속도 향상보다 잘못된 실행을 제거한 것이다.

| 비교 항목 | 적용 전 위험 | 적용 후 기대 결과 |
| --- | --- | --- |
| 오프라인 Queue 만료 Command | 재연결 후 전달 가능 | Broker에서 0건 전달 |
| 오래된 Telemetry DB 쓰기 | Handler 도달 가능 | Handler 전 0건 |
| 미래 Timestamp | 최신값으로 오인 가능 | 5초 초과 시 거부 |
| 만료 Command Result | 상태 재변경 가능 | INSERT·상태 변경 0건 |

포트폴리오에는 같은 입력 N건을 Guard 활성·비활성 조건에 넣어 잘못된 DB 쓰기
건수와 정상 메시지 처리량을 함께 기록한다. Guard의 비용을 설명하려면 정상
메시지 p95 처리시간과 초당 처리량도 비교하되, 현재 문서에는 측정되지 않은 값을
작성하지 않는다.

## 7. 주의사항

- Robot과 Server의 NTP 동기화가 깨지면 정상 메시지가 미래시각으로 거부될 수 있다.
- 경계는 `age >= maxDelay`일 때 만료이므로 정확히 60초도 허용되지 않는다.
- Event 재전송은 최초 `occurred_at`, `ttl_sec`를 유지해야 한다. 재전송 시
  `occurred_at`을 갱신하면 만료시간이 부당하게 연장된다.
- Presence는 Retained/LWT 특성과 공통 사건시각 부재 때문에 TTL Guard 대상이
  아니다. Presence 재생은 `boot_id`·`seq` 정책으로 분리한다.
- 실제 EC2 Broker와 Jetson에서의 Message Expiry는 아직 검증하지 않았다.

## 8. 장애 확인 순서

1. Payload 기준시각과 Server 수신시각의 Timezone이 UTC인지 확인한다.
2. Topic별 최대 지연 환경변수를 확인한다.
3. `gilbom.mqtt.inbound.rejected`의 `topic`, `reason` 증가를 확인한다.
4. 거부 메시지가 Dispatcher와 업무 Handler에 도달하지 않았는지 확인한다.
5. Command의 `expires_at`과 MQTT 5 Message Expiry가 같은 TTL에서 계산됐는지 본다.
6. Command Retain이 `false`인지 확인한다.
7. Persistent Session 재연결 시 `sessionPresent`와 실제 수신 메시지를 대조한다.

## 9. 관련 파일

- `web/backend/src/main/java/com/gilbom/robot/mqtt/parser/MqttInboundFreshnessGuard.java`
- `web/backend/src/main/java/com/gilbom/robot/mqtt/config/MqttInboundFreshnessProperties.java`
- `web/backend/src/main/java/com/gilbom/robot/mqtt/client/MqttPublisher.java`
- `web/backend/src/test/java/com/gilbom/robot/mqtt/client/MqttPublisherBrokerE2E.java`
- `web/backend/src/test/java/com/gilbom/robot/mqtt/client/MqttInboundBrokerE2E.java`
- `web/docs/final-mqtt-topic-schema-v2.md`
- `TROUBLESHOOTING/8_MqttInboundEventBrokerE2E.md`
- `CHANGLOGS/2026-07-29_1339_mqtt-inbound-ttl-guard.md`
- `CHANGLOGS/2026-07-29_1859_mqtt-publisher-broker-e2e.md`
