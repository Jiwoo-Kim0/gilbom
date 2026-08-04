# MQTT v2 계약·TLS·ACL·Credential 구현 내용

> 담당: 팀원 B — Robot MQTT 통신
> 작업일: 2026-07-27 ~ 2026-07-30
> 연결 Jira: `S15P11D101-366`, `S15P11D101-432`, `S15P11D101-434`
> 최종 상태: **DONE (LOCAL)** — 로컬 계약·TLS Broker 검증 완료, Jetson·EC2 대기

관련 정본:
[MQTT v2 계약](../../final-mqtt-topic-schema-v2.md),
[계약 동결 가이드](../../mqtt-v2-contract-freeze.md),
[AsyncAPI](../../asyncapi-mqtt-v2.yaml),
[기기-DB 매핑](../../mqtt-device-db-mapping.md)

---

## 0. 3분 요약

외부 Robot 식별자를 `mqtt_id`, DB 내부 식별자를 `robot_id UUID`로 분리하고
MQTT Namespace를 `tbs/v2/robots/{mqtt_id}/...`, Payload 버전을 문자열
`"2.0"`으로 고정했다.

사람용 계약만 작성한 것이 아니다. 다음을 한 묶음으로 동결했다.

- AsyncAPI 3.1.0
- JSON Schema Draft 07 파일 12개
- 정상·오류·Replay Fixture 33개
- Manifest 기대 결과
- 자동 계약 테스트
- Backend·Embedded 구현 대조

통신 보안은 TLS, Robot별 Topic ACL, Credential 회전·폐기와 Mosquitto
Password Registry 정합성까지 로컬 실제 Broker로 검증했다. Event TTL·미래 시각,
이전 Boot Replay, 같은 `(boot_id, seq)` 충돌, 정확한 재전송 멱등성도
Broker→Backend→PostgreSQL 흐름에서 확인했다.

아직 남은 것은 실제 Jetson에 현재 Python을 배포하고 EC2 Broker의 운영
Credential·인증서로 같은 결과를 확인하는 일이다.

---

## 1. 요구사항과 수용 기준

### 1.1 식별자

| 값 | 의미 | 사용 위치 |
| --- | --- | --- |
| `robot_id UUID` | DB 내부 PK | FK·REST 내부 식별 |
| `mqtt_id` | 외부 Robot 식별 | Topic 경로·Payload·Broker username |
| `mqtt_client_id` | MQTT Session 식별 | 동시 접속·Persistent Session |
| `boot_id UUIDv4` | 통신 Process 수명 | Replay·재부팅 구분 |
| `seq >= 1` | 같은 Boot의 공용 순번 | State·Telemetry·Event 교차 Topic |

Topic의 `{mqtt_id}`와 Payload `mqtt_id`가 다르면 업무 Service 전에 거부해야 한다.
REST의 `robotId` UUID와 MQTT의 `mqtt_id` 문자열을 다시 혼용하면 안 된다.

### 1.2 Topic·방향

Backend 관점에서 `receive`는 Robot/Broker에서 수신하고, `send`는 Robot으로
발행한다.

| 방향 | Topic |
| --- | --- |
| receive | `presence` |
| receive | `state` |
| receive | `telemetry` |
| receive | `events/incident` |
| receive | `events/battery` |
| receive | `events/safety` |
| receive | `events/mission` |
| receive | `commands/result` |
| send | `commands/request` |
| send | `ingest/receipt` |

### 1.3 전송 의미

- Presence·State·Event·Command·Result: QoS 1
- Telemetry: QoS 0
- Retain:
  - State·Presence는 계약에 정한 최신 Snapshot/LWT 의미만 허용
  - Command Request는 `false`
- Event `ttl_sec`: 60~300초
- Command: MQTT 5 Message Expiry와 JSON `expires_at`을 함께 적용
- 재전송:
  - Event ID·Boot·Sequence·발생시각·TTL 불변
  - `sent_at`만 갱신 가능

### 1.4 완료 판정

- AsyncAPI와 모든 Schema가 파싱된다.
- 정상 Fixture는 통과하고 부정 Fixture는 기대 위치에서 실패한다.
- Topic/Payload ID, 시각 선후, bbox 순서, Replay 같은 JSON Schema 외 규칙도
  Semantic Test로 검증한다.
- Robot은 자기 Publish Topic과 자기 Command Subscribe만 허용된다.
- 다른 Robot Topic·Server 전용 역방향 Publish는 거부된다.
- Credential 원문은 발급 시 한 번만 보이며 조회·로그·감사에 남지 않는다.
- 실제 Jetson·EC2를 실행하지 않았다면 상태를 `DONE (LOCAL)`로 유지한다.

---

## 2. 무엇을 만들었나

### 2.1 Backend MQTT 구조

```text
com.gilbom.robot.mqtt
├─ client       Subscriber·Publisher·MQTT v2 Publisher
├─ config       연결·Freshness 설정
├─ topic        Topic Parser·Type·방향
├─ parser       JSON 파싱·Freshness Guard
├─ handler      Topic별 Handler·Dispatcher
├─ replay       Boot·Sequence·Event 멱등성
└─ strategy     TLS·mTLS 보안 전략
```

DTO는 `com.gilbom.robot.data.mqtt.v2` 아래 공통·수신·발신으로 분리했다.

### 2.2 계약 산출물

```text
web/docs/asyncapi-mqtt-v2.yaml
web/docs/mqtt-v2/schemas/*.schema.json
test/fixtures/mqtt/v2/manifest.json
test/fixtures/mqtt/v2/valid/*.json
test/fixtures/mqtt/v2/invalid/*.json
test/mqtt_contract/test_mqtt_v2_contract.py
```

Manifest에는 Schema 성공 여부만 넣지 않고 다음 처리 결과를 함께 고정했다.

- `processing`
- `error_code`
- `db_insert`
- `current_state_update`
- `row_delta`
- `execute_command`

### 2.3 Replay·Freshness

수신 순서는 다음과 같다.

```text
Topic Parse
→ Payload Parse·Bean Validation
→ Topic/Payload mqtt_id 일치
→ Freshness Guard
→ Boot·Sequence Replay 판정
→ 업무 Handler
→ DB Commit
→ Receipt·SSE
```

업무 Handler보다 앞에서 만료·미래·Replay를 막기 때문에 거부 Event가
Mission·Defect·History에 부분 반영되지 않는다.

### 2.4 ACL·Credential

- Robot Credential 발급·회전
- Secret 없는 Credential 이력 조회
- ACTIVE Credential 즉시 폐기
- Robot 잠금 기반 버전 직렬화
- Mosquitto Password File Cutover
- DB Registry와 Broker Registry Drift 조회·복구
- 주기 Drift Job

Robot별 ACL은 자기 Namespace만 허용한다. Backend는 전체 Robot Namespace를
수신하고 Command·Receipt를 발행한다.

---

## 3. 설계 판단과 그 이유

### 3.1 AsyncAPI 버전과 Payload 버전은 다르다

`asyncapi: 3.1.0`은 문서 형식 버전이고 `"schema_version": "2.0"`은 길봄 MQTT
계약 버전이다. 둘을 같은 값으로 맞추지 않는다.

### 3.2 선택 필드 추가도 Major 변경으로 본다

Schema가 `additionalProperties: false`이므로 엄격한 소비자는 모르는 필드도
거부한다. Topic·필수 필드·enum뿐 아니라 선택 필드 추가도 새 Namespace와
Payload 버전이 필요하다.

### 3.3 JSON Schema가 못 하는 규칙은 별도 Semantic Test로 둔다

다음은 파일 하나의 JSON 구조만으로 판정할 수 없다.

- `issued_at < expires_at`
- Topic `{mqtt_id}`와 Payload `mqtt_id`
- bbox 최소·최대 원소 순서
- 기존 DB와 비교하는 Sequence·Replay
- 같은 Command 재전달 시 실행 여부

이 규칙을 문서 산문으로만 두면 회귀를 잡지 못하므로 Manifest와 Python Test로
고정했다.

### 3.4 Broker Expiry와 업무 Expiry를 둘 다 둔다

JSON 안의 `expires_at`은 Broker가 읽지 않는다. 오프라인 Persistent Session에
Command가 쌓이면 재연결 뒤 만료 Command가 전달될 수 있다.

그래서:

- Broker: MQTT 5 Message Expiry로 전달 자체를 차단
- Backend·Robot: `expires_at`으로 업무 실행을 다시 차단

두 방어선은 중복이 아니라 서로 다른 구간을 보호한다.

### 3.5 QoS 1은 중복 가능성을 전제로 한다

QoS 1은 “정확히 한 번”이 아니다. Backend와 Robot 양쪽에 멱등 키가 필요하다.

- Event: `event_id`
- Telemetry·State: `(boot_id, seq)`와 message identity
- Command REST: `Idempotency-Key`
- Robot 실행: `command_id`

### 3.6 API Key와 MQTT Credential을 합치지 않았다

이미지 Upload API Key는 HTTP 파일 업로드 권한이고 MQTT Credential은 Broker
연결·Topic ACL 권한이다. 폐기·회전 영향 범위가 달라 별도 수명주기로 유지한다.

---

## 4. 검증과 재현 명령

### 계약 검증

```powershell
$env:PYTHONIOENCODING='utf-8'
python -m unittest discover -s test/mqtt_contract -p 'test_*.py' -v
```

완료 로그와 2026-07-30 재검증 기준 결과:

- MQTT 계약 Test 9개 통과
- Embedded 전체 479개 통과
- Backend MQTT 집중 18개 통과

Embedded 479개와 Backend MQTT 집중 18개는 기존 완료 로그의 결과이고, 이번
문서 정리에서는 MQTT 계약 Test 9개만 다시 실행했다.

### Backend Broker E2E

```powershell
cd web/backend
.\gradlew.bat mqttBrokerE2E --rerun-tasks
```

검증 내용:

- 짧은 Command Expiry 미전달
- 긴 Command 재연결 후 전달
- Event 만료·미래 시각 거부
- 정확한 재전송 멱등
- 같은 Sequence의 다른 Event 충돌

### ACL·Credential

로컬 TLS Mosquitto에서 확인한 항목:

- 자기 Topic Publish 성공
- 자기 Command Subscribe 성공
- 타 Robot Command 수신 0건
- Server 전용 Command Request 역방향 Publish 거부
- 회전 전 Secret 차단
- 회전 후 Secret 연결
- 폐기 직후 재연결 차단
- Registry Drift 탐지·복구

---

## 5. 작업 중 겪은 함정

### v1 문서가 함께 남아 있다

과거 PDF와 v1 Markdown을 삭제하지 않았기 때문에 검색하면 `tbs/v1`,
`robot_id`, `"1.0"`이 나온다. 보관본 상단의 Deprecated 표시를 확인하고 신규
구현은 v2 동결 묶음만 사용한다.

### Java DTO의 `isXxx()`가 Wire 필드가 됐다

검증용 boolean 메서드가 Jackson property로 직렬화돼
`additionalProperties: false`에 걸린 적이 있다. Wire ObjectMapper에서는
불필요한 is-getter 자동 탐지를 차단하고 실제 Fixture를 검사한다.

### 설정 Bean이 빠진 최소 Test Context

실제 Handler만 가져온 통합 테스트에서 Freshness Properties가 없어 Context가
뜨지 않았다. 최소 Context도 운영 경로가 요구하는 설정 Bean을 명시해야 한다.

### Flyway와 계약 파일은 일부만 옮기면 안 된다

이미 적용한 Migration 수정은 checksum을 깨고, MQTT 계약 파일 일부만 변경하면
사람 문서와 기계 검증이 갈린다. 둘 다 “묶음 단위 변경”으로 취급한다.

---

## 6. 미완 항목

### 내 몫

- 실제 Jetson·EC2 통합 결과를 작업 문서에 추가
- Command Broker/Browser 전체 E2E
- 다중 Backend SSE Fan-out 필요 여부 확정
- 실제 운영 환경에서 MQTT Client ID 중복 연결 정책 확인

### 팀원 A

- EC2 Mosquitto TLS Endpoint·보안그룹
- 운영 인증서·Secret 주입
- Nginx SSE Buffering 설정
- Backend 다중 인스턴스 여부 통보

### 팀원 C

- Incident·Image·bbox 계약 변경 시 v2 Major 절차 준수
- Image V31 정본 유지와 이후 Migration 번호 선점 사항 공유

### Embedded팀

- 실제 Jetson에 동결된 v2 구현 배포
- UUIDv4 Boot·공용 Sequence·NTP·발행 주기 증거
- 같은 Command 재수신 시 중복 실행 방지

---

## 7. 관련 문서와 기록

- `CHANGLOGS/2026-07-30_1145_mqtt-v2-contract-freeze.md`
- `CHANGLOGS/2026-07-30_0929_mqtt-inbound-event-broker-e2e.md`
- `TROUBLESHOOTING/3_MqttTtlAndMessageExpiry.md`
- `TROUBLESHOOTING/4_MqttReplayProtection.md`
- `TROUBLESHOOTING/5_MqttClientTopicAcl.md`
- `TROUBLESHOOTING/6_MqttCredentialRegistry.md`
- `TROUBLESHOOTING/8_MqttInboundEventBrokerE2E.md`
- `TROUBLESHOOTING/9_MqttV2ContractFreeze.md`
