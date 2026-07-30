# MQTT Event TLS Broker→DB E2E 실패와 해결

## 1. 한 줄 요약

Backend 단위 테스트가 통과해도 Robot Wire Payload에 `ttl_sec`가 없거나 식별자 키가
`robot_id`이면 실제 v2 Parser에 도달할 수 없다. Embedded 발행 Payload를
`mqtt_id`·`ttl_sec` 계약에 맞추고, 실제 TLS Broker에서 만료·미래 Event의
DB 무부작용과 동일 Event 재전송 멱등성을 검증했다.

## 2. 문제 현상

- Embedded `MessageFactory`가 Event 공통 Envelope에 필수 `ttl_sec`를 넣지 않았다.
- Embedded Wire Payload가 MQTT 외부 식별자 `mqtt_id` 대신 DB 내부 식별자 이름인
  `robot_id`를 사용했다.
- 새 Broker E2E의 첫 실행은 Spring Test Context가 기동되지 않았다.
- 설정 Bean을 보강한 두 번째 실행에서는 Broker가 메시지를 전달했지만
  `MQTT_PAYLOAD_MALFORMED`로 거부됐다.

## 3. 원인

### 3.1 정본과 Embedded Payload의 불일치

`final-mqtt-topic-schema-v2.md`는 Topic·username·Payload에서 `mqtt_id`를 사용하고,
Event의 `ttl_sec`를 `60~300` 범위의 필수 정수로 정의한다. Embedded 발행기는
이전 키인 `robot_id`를 유지했고 `ttl_sec`를 생성하지 않았다.

### 3.2 선택적 Test Context에 설정 Bean을 포함하지 않음

E2E는 전체 애플리케이션 대신 MQTT Parser·Handler·수집 Service만 가져오는
최소 Spring Context를 사용한다. 첫 구성에
`MqttInboundFreshnessProperties`를 Import하지 않아
`MqttInboundFreshnessGuard` 생성자 의존성을 만족하지 못했다.

대표 오류:

```text
No qualifying bean of type
'com.gilbom.robot.mqtt.config.MqttInboundFreshnessProperties'
```

### 3.3 DTO 검증 메서드가 테스트 Wire JSON에 섞임

테스트 Publisher가 Java DTO를 기본 Jackson 설정으로 직렬화하면서
`@AssertTrue` 검증 메서드인 `isSourceValid`,
`isResultPresenceValid`, `isValidityConsistent` 등을 JSON 속성으로 추가했다.
운영 Parser는 `FAIL_ON_UNKNOWN_PROPERTIES`를 사용하므로 실제 계약에 없는 이
속성들을 `MQTT_PAYLOAD_MALFORMED`로 거부했다.

이 문제는 Broker·Parser의 결함이 아니라 테스트 Publisher가 실제 Robot JSON보다
많은 필드를 발행한 것이 원인이었다.

## 4. 해결

### 4.1 Embedded Wire Payload 정합화

- Presence·State·Telemetry·Event·Command Result의 외부 식별자 키를
  `mqtt_id`로 통일했다.
- Event 공통 Envelope에 기본 `ttl_sec=300`을 추가했다.
- 허용 범위 `60~300` 밖의 값, 실수, Boolean은 발행 전에 거부한다.
- Outbox 재전송은 기존 `event_id`, `boot_id`, `seq`, `occurred_at`,
  `ttl_sec`를 유지하고 `sent_at`만 갱신한다.

### 4.2 최소 Spring Context 의존성 보강

E2E Test Application에 `MqttInboundFreshnessProperties`를 명시적으로 Import해
실제 `MqttInboundFreshnessGuard` 기본값과 Metric을 사용하게 했다.

### 4.3 실제 Wire 필드만 직렬화

테스트용 `JsonMapper`에서 `AUTO_DETECT_IS_GETTERS`를 비활성화했다. Record
Component는 그대로 직렬화되지만 검증용 `isXxx()` 메서드는 Wire JSON에서
제외된다. Snake Case와 Java Time 직렬화는 유지한다.

## 5. 실제 E2E 시나리오

```text
Mock Robot Paho MQTT v5 Client
  → TLS Mosquitto, QoS 1, Retain false
  → Backend 구독 Client
  → MqttInboundMessageDispatcher
  → MqttMessageParser
  → MqttInboundFreshnessGuard
  → Mission/Incident Handler
  → Replay Protection
  → PostgreSQL/PostGIS Testcontainer
```

1. `occurred_at=현재-61초`, `ttl_sec=60` Mission Event를 발행한다.
2. `occurred_at=현재+30초` Incident Event를 발행한다.
3. 두 Event가 각각 `expired`, `future_timestamp` Metric을 증가시키고
   Event·Mission History·Defect·Observation을 만들지 않는지 확인한다.
4. 새 Boot의 `seq=1` 정상 Mission Event를 발행한다.
5. 완전히 같은 Payload를 한 번 더 발행한다.
6. 같은 `(boot_id,seq)`를 다른 `event_id`의 Incident Event가 재사용하게 한다.
7. 충돌 거부 Metric이 증가하고 `robot_event`와 `mission_status_history`가
   각각 1건이며 Mission 전이가
   한 번만 적용됐는지 확인한다.

## 6. 검증 결과

- Embedded MQTT 집중 회귀: `373 passed`
- Embedded 전체 회귀: `479 passed`
- Backend Parser·Event 집중 회귀: `22 tests`, 실패 0, 생략 0
- Backend 전체 회귀: `542 tests`, 실패 0, 생략 0
- 실제 TLS Broker E2E: `2 tests`, 실패 0, 생략 0
  - 기존 Command Message Expiry: 통과
  - 신규 Event TTL·미래시각·정확한 재전송·순번 충돌 Broker→DB: 통과

실패 이력:

1. 설정 Bean 누락으로 Context 기동 실패
2. 테스트 Publisher의 추가 `isXxx` JSON 속성으로
   `MQTT_PAYLOAD_MALFORMED`
3. 두 원인 수정 후 전체 Broker E2E 통과

## 7. 장애 확인 순서

1. 정본이 `final-mqtt-topic-schema-v2.md`인지 확인한다.
2. Topic `{mqtt_id}`, MQTT username, Payload `mqtt_id`가 같은지 확인한다.
3. Event에 `ttl_sec`가 있고 `60~300` 범위인지 확인한다.
4. 재전송에서 `event_id`, `boot_id`, `seq`, `occurred_at`, `ttl_sec`가
   바뀌지 않았는지 확인한다.
5. Parser 거부 코드가 `MALFORMED`, `VALIDATION_FAILED`, `EXPIRED`,
   `TIMESTAMP_IN_FUTURE` 중 무엇인지 먼저 구분한다.
6. 부분 Spring Context라면 Freshness Properties와 Meter Registry가 Bean으로
   등록됐는지 확인한다.
7. DTO를 직렬화하는 Test Publisher라면 검증용 `isXxx()` 메서드가 JSON에
   섞이지 않았는지 원문 Payload를 확인한다.
8. 거부 뒤 Event·Mission·Defect Table Count와 현재 Boot가 변하지 않았는지
   함께 확인한다.

## 8. 주의사항

- `ttl_sec`는 `sent_at`이 아니라 `occurred_at` 기준이다.
- Event를 재전송하며 `occurred_at`을 현재시각으로 바꾸면 만료가 부당하게
  연장되므로 금지한다.
- `robot_id`는 DB 내부 UUID다. MQTT Wire에는 `mqtt_id`만 사용한다.
- DTO를 그대로 JSON으로 만드는 테스트는 실제 기기 Payload보다 필드가 많아질 수
  있으므로 계약 Fixture 또는 Wire 전용 Mapper를 사용한다.
- 이번 검증은 로컬 TLS Mosquitto와 Mock Publisher 기준이다. EC2 Broker와 실제
  Jetson 검증은 Day 7 범위다.

## 9. 관련 파일

- `embedded/comms/contract.py`
- `embedded/comms/factory.py`
- `web/backend/src/test/java/com/gilbom/robot/mqtt/client/MqttInboundBrokerE2E.java`
- `web/backend/src/main/java/com/gilbom/robot/mqtt/parser/MqttInboundFreshnessGuard.java`
- `web/backend/src/main/java/com/gilbom/robot/mqtt/replay/MqttReplayProtectionService.java`
- `web/docs/final-mqtt-topic-schema-v2.md`
- `TROUBLESHOOTING/3_MqttTtlAndMessageExpiry.md`
- `TROUBLESHOOTING/4_MqttReplayProtection.md`
