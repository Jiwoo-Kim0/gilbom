# MQTT v2 계약 동결·검증 가이드

- 계약 버전: `2.0`
- 상태: `FROZEN`
- 동결 일자: 2026-07-30 (Asia/Seoul)
- Namespace: `tbs/v2/robots/{mqtt_id}/...`

## 1. 동결 묶음

다음 파일은 하나의 계약으로 함께 관리한다.

| 구분 | 정본 |
|---|---|
| 사람이 읽는 Topic·Payload 계약 | `final-mqtt-topic-schema-v2.md` |
| Topic·방향·QoS·Retain·Message 정의 | `asyncapi-mqtt-v2.yaml` |
| Payload 필드·enum·범위·조건부 규칙 | `mqtt-v2/schemas/*.schema.json` |
| 정상·거부·중복·Replay 입력과 예상 결과 | `../../test/fixtures/mqtt/v2/manifest.json` |
| 자동 계약 검증 | `../../test/mqtt_contract/test_mqtt_v2_contract.py` |

AsyncAPI 문서 버전 `3.1.0`은 문서 형식의 버전이고 MQTT Payload 계약 버전
`2.0`과 다른 값이다. Payload Schema는 AsyncAPI 도구 호환성을 위해 JSON Schema
Draft 07을 사용한다.

## 2. 동결 대상과 환경값

동결 대상:

- 10개 Topic의 경로, 방향, QoS, Retain과 MQTT 5 Message Expiry
- 식별자, 필수·선택 멤버, 타입, enum, UUID·UTC·숫자 범위
- `valid=false`일 때 위치·배터리 Null 규칙
- `mission.elapsed_sec`는 Mission 시작 후의 단조 증가 벽시계 누적 초이며
  `PAUSED` 중에도 증가한다. 서버 진행률은 이 값과 Mission 계획시간으로 계산하고
  Route Geometry·GPS 유효 여부에는 의존하지 않는다.
- Event TTL, Topic/Payload `mqtt_id`, Replay·Sequence·중복 처리
- DB INSERT, Current State 갱신, Command 실행 여부와 오류 코드

동결하지 않는 환경값:

- Broker 실제 Host·Port와 배포 환경 이름
- MQTT Password, 인증서·Private Key와 Secret 경로
- 운영 Robot의 실제 `mqtt_id`, `mqtt_client_id`
- 운영 DB 주소와 사용자별 Credential

Fixture에는 실제 Secret·Credential을 넣지 않는다.

## 3. 검증 실행

Repository Root에서 실행한다.

```powershell
$env:PYTHONIOENCODING='utf-8'
python -m unittest discover -s test/mqtt_contract -p 'test_*.py' -v
```

검증 범위:

1. 모든 JSON Schema가 Draft 07 Meta Schema를 만족하는지 확인한다.
2. AsyncAPI의 Topic 10개, Operation 방향, QoS, Retain과 Schema 참조를 확인한다.
3. 모든 Fixture가 Manifest에 등록됐는지 확인한다.
4. 정상 Fixture는 Schema를 통과하고 형식 오류 Fixture는 실패하는지 확인한다.
5. Topic/Payload 불일치, QoS, TTL, 중복 Event, Sequence 충돌, 이전 Boot,
   Command 만료·중복 같은 처리 시나리오를 확인한다.
6. Fixture에 Password·Secret·API Key·Private Key가 없는지 확인한다.

JSON Schema만으로 두 시각의 선후 관계, `bbox_norm` 원소 간 대소 관계, Topic
경로와 Payload 값의 일치, DB의 기존 행과 비교하는 Replay 판정을 표현할 수 없다.
이 규칙은 Manifest의 `semantic_check`와 자동 Test에서 별도로 검증한다.

## 4. Fixture 예상 결과

각 Manifest 항목은 최소 다음 결과를 갖는다.

| 필드 | 의미 |
|---|---|
| `schema_valid` | 선택한 JSON Schema 통과 여부 |
| `processing` | `ACCEPTED`, `REJECTED`, `IDEMPOTENT`, `EXPIRED_RESULT` 등 |
| `error_code` | 거부 시 Backend 계약 오류 코드 |
| `db_insert` | 새 이력·결과 행 INSERT 여부 |
| `current_state_update` | `robot_current_state` 갱신 여부 |
| `row_delta` | 해당 Fixture 처리로 증가하는 정본 행 수 |
| `execute_command` | Robot이 Command를 실제로 실행하는지 여부 |

중복 Event 재전송은 `sent_at`만 달라질 수 있고 새 행을 만들지 않는다. 같은
`(mqtt_id, boot_id, seq)`를 다른 메시지가 사용하면
`MQTT_SEQUENCE_CONFLICT`로 거부한다. 교체된 Boot의 지연 메시지는
`MQTT_REPLAY_REJECTED`로 거부한다.

## 5. 동결 후 변경 절차

다음 변경은 호환성을 깨므로 v2 파일을 직접 확장하지 않는다.

- Topic 경로, QoS, Retain 변경
- 필수 여부, 타입, Null 의미, 숫자 범위 변경
- enum 값 추가·삭제·의미 변경
- Payload에 선택 필드 추가
- TTL·중복·Replay·DB 처리 의미 변경

엄격한 소비자는 알 수 없는 선택 필드도 거부하므로 필드 추가 역시 Major 변경으로
취급한다. 변경이 필요하면 새 Namespace와 `schema_version`을 만들고, 구·신 버전의
병행 기간과 제거 일정을 별도 문서에 기록한다.

Wire에 영향이 없는 설명·오탈자 정정은 v2에서 허용한다. 이 경우에도 다음을 모두
수행한다.

1. 사람이 읽는 계약과 기계 판독 정본을 함께 확인한다.
2. 정상·오류 Fixture와 예상 결과를 대조한다.
3. 자동 계약 검증과 관련 Backend·Embedded Test를 실행한다.
4. `CHANGLOGS/`에 변경·검증 결과를 기록한다.
