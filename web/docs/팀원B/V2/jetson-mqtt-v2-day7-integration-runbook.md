# Jetson MQTT v2 Day 7 통합 실행·검증 가이드

> 문서 상태: 실기 통합 실행 정본
>
> 작성 기준: 2026-08-01 (Asia/Seoul)
>
> 대상: 팀원 B, Embedded팀, Jetson 배포·검증 AI
>
> Wire 계약: [`final-mqtt-topic-schema-v2.md`](../../final-mqtt-topic-schema-v2.md)

이 문서는 MQTT v2 Wire 계약을 변경하지 않는다. Topic, Payload, 필드,
enum, QoS, Retain의 정본은 위 Wire 계약과 그 문서가 참조하는
AsyncAPI·JSON Schema다. 이 문서는 현재 `embedded` 코드를 실제 Jetson에
배포하고 EC2 운영 Broker와 Day 7 종단 간 통합을 검증하는 절차다.

Secret 원문, Password, Private Key, Credential 발급 응답 전체를 Git,
Jira, 메신저, 로그, 화면 캡처에 남기지 않는다.

---

## 1. 현재 상태와 완료 판정

| 단계 | 현재 상태 | 의미 |
| --- | --- | --- |
| MQTT v2 Schema·Fixture | `LOCAL PASS` | 계약 자동 테스트 9개 통과 |
| Mock Robot·로컬 Broker | `LOCAL PASS` | Topic·Payload·Command 흐름 검증 |
| EC2 Broker 내부 | `EC2 INTERNAL PASS` | TLS, Backend 계정, Broker Health 통과 |
| 외부 Jetson 접속 | `WAITING` | 실제 Robot Credential·ACL 왕복 미검증 |
| Day 7 전체 | `NOT DONE` | Jetson→EC2→DB→SSE/UI→Command Result 전체 증거 필요 |

Mock 테스트를 다시 통과해도 `JETSON PASS`로 판정하지 않는다. 실제
장비나 EC2 접근 조건이 없으면 해당 항목을 `WAITING`으로 남긴다.

## 2. Day 7 완료 게이트

Day 7은 아래가 모두 통과해야 `DONE`이다.

1. 실제 Jetson이 인증서 검증을 끈지 않고 EC2 TLS Broker에 접속한다.
2. Presence→State→Telemetry가 Backend·DB·REST/SSE·React까지 반영된다.
3. Jetson과 서버의 시간이 동기화되고 v2 Payload가 Schema와 일치한다.
4. 지원하는 6개 Command가 ACK와 최종 Result를 반환한다.
5. 네트워크 단절·복구, LWT, 재구독, Replay, Expiry를 실제 환경에서 확인한다.
6. 자기 Robot Topic만 허용되고 타 Robot·역방향 Topic은 ACL로 거부된다.
7. Secret 없는 실행 증거와 실패·미실행 항목을 새 CHANGLOG에 남긴다.

## 3. 정본과 구현 경계

- Wire 계약: [`final-mqtt-topic-schema-v2.md`](../../final-mqtt-topic-schema-v2.md)
- Schema: [`mqtt-v2/schemas/`](../../mqtt-v2/schemas/)
- AsyncAPI: [`asyncapi-mqtt-v2.yaml`](../../asyncapi-mqtt-v2.yaml)
- 계약 동결·검증: [`mqtt-v2-contract-freeze.md`](../../mqtt-v2-contract-freeze.md)
- Embedded 실행 설정: [`embedded/config.yaml`](../../../../embedded/config.yaml)
- Embedded 설정 Loader: [`embedded/comms/config.py`](../../../../embedded/comms/config.py)
- Broker ACL: [`web/infra/mosquitto/acl`](../../../infra/mosquitto/acl)

기존 [`로봇 MQTT 환경변수 및 Credential 전달.md`](<./로봇 MQTT 환경변수 및 Credential 전달.md>)는
설계 배경으로만 참고한다. 해당 문서의 “Embedded가 TODO 골격”이라는 상태는
현재 코드와 다르다. Jetson 실행 시에는 반드시 이 문서와 실제 코드를 우선한다.

## 4. 실제 운영 접속 값

| 항목 | 현재 기준 |
| --- | --- |
| Broker Host | `i15d101.p.ssafy.io` |
| Broker Port | `8883` |
| Protocol | MQTT 5 over TLS |
| 인증 | Robot별 username/password |
| username | 발급된 `mqtt_id`와 동일 |
| Client ID | 해당 Jetson 프로세스에서 고유한 값 |
| Payload Version | `"schema_version":"2.0"` |

현재 운영 기본은 서버 TLS + username/password다. 로봇 Client Certificate를
요구하는 mTLS Profile을 적용했다고 가정하지 않는다. 운영 설정이 mTLS로
변경됐다면 팀원 A에게 Client Certificate·Private Key 주입 방식을 받아 별도로
기록한다.

## 5. 실행 전 Gate

- [ ] EC2 Mosquitto와 Backend가 Healthy다.
- [ ] EC2 보안그룹이 Jetson 출발지에서 TCP 8883을 허용한다.
- [ ] `i15d101.p.ssafy.io` 인증서의 유효기간·Chain·Hostname이 유효하다.
- [ ] Backend의 MQTT 운영 접속이 Healthy다.
- [ ] 대상 Robot이 ACTIVE이고 `mqtt_id`가 확정됐다.
- [ ] Credential Provisioning이 활성화됐다.
- [ ] 대상 Robot Credential을 발급했고 비밀번호 원문을 안전하게 전달했다.
- [ ] Jetson 시각이 NTP로 동기화됐다.
- [ ] 실물 로봇을 움직일 안전 공간과 현장 긴급 정지 수단이 준비됐다.

Credential 원문을 구할 수 없는 경우 기존 값을 DB에서 조회하지 않고 재발급한다.
재발급은 기존 비밀번호를 무효화하므로 Jetson 설정 교체·재시작 시간을 먼저
확보한다.

## 6. Jetson 설정

### 6.1 현재 코드가 읽는 값

`embedded/config.yaml`은 현재 다음 두 환경변수를 직접 읽는다.

```dotenv
MQTT_HOST=i15d101.p.ssafy.io
MQTT_PASSWORD=<일회 발급받은 Robot Secret>
```

`MQTT_BROKER_HOST`, `MQTT_CA_CERT` 등의 이름을 설정해도 현재 Loader가 자동으로
읽지 않는다. 배포 전 반드시 `embedded/config.yaml`과
`embedded/comms/config.py`를 기준으로 확인한다.

### 6.2 `embedded/config.yaml` 운영 설정

실제 발급값은 커밋하지 않고 Jetson 내 보호된 배포 사본에만 적용한다.

```yaml
robot:
  id: <발급된 mqtt_id>

mqtt:
  host: ${env:MQTT_HOST}
  port: 8883
  username: ${robot.id}
  client_id: <고유한 mqtt_client_id>
  password: ${env:MQTT_PASSWORD}
  keepalive: 30
  ca_cert: /etc/ssl/certs/ca-certificates.crt
  require_tls: true
  outbox_path: var/outbox.sqlite3
```

중요 조건:

- `robot.id = mqtt.username = Topic {mqtt_id} = Payload mqtt_id`
- `client_id`는 username과 다른 세션 식별자며 동시 접속하는 Client끼리 고유해야 한다.
- `require_tls: true`와 유효한 `ca_cert`를 함께 사용한다.
- TLS 검증을 끄거나 평문 1883으로 우회하지 않는다.
- 비밀번호를 Shell 명령 인자, Process 목록, 로그에 노출하지 않는다.

### 6.3 실행

현재 Entry Point는 별도 CLI 인자 없이 `embedded/config.yaml`을 읽는다.

```bash
cd /opt/gilbom/embedded
python main.py
```

실제 서비스는 보호된 EnvironmentFile을 읽는 systemd로 실행하고,
`network-online.target`과 `time-sync.target` 이후에 시작하도록 구성한다.

## 7. Topic·Wire 확인

### 7.1 Jetson 발행

| Topic | QoS | Retain |
| --- | ---: | :---: |
| `tbs/v2/robots/{mqtt_id}/presence` | 1 | O |
| `tbs/v2/robots/{mqtt_id}/state` | 1 | O |
| `tbs/v2/robots/{mqtt_id}/telemetry` | 0 | X |
| `tbs/v2/robots/{mqtt_id}/events/incident` | 1 | X |
| `tbs/v2/robots/{mqtt_id}/events/battery` | 1 | X |
| `tbs/v2/robots/{mqtt_id}/events/safety` | 1 | X |
| `tbs/v2/robots/{mqtt_id}/events/mission` | 1 | X |
| `tbs/v2/robots/{mqtt_id}/commands/result` | 1 | X |

### 7.2 Jetson 구독

| Topic | QoS | Retain |
| --- | ---: | :---: |
| `tbs/v2/robots/{mqtt_id}/commands/request` | 1 | X |
| `tbs/v2/robots/{mqtt_id}/ingest/receipt` | 1 | X |

### 7.3 Payload 필수 확인

- [ ] `schema_version` 값은 String `"2.0"`이다.
- [ ] `mqtt_id`가 Topic의 `{mqtt_id}`와 같다.
- [ ] Process 시작 시 UUIDv4 `boot_id`를 새로 생성한다.
- [ ] State·Telemetry·Event가 한 Boot에서 하나의 공용 `seq`를 증가시킨다.
- [ ] 새 Boot의 첫 순번은 `seq=1`이다.
- [ ] 시각은 UTC ISO 8601 `Z`다.
- [ ] Telemetry는 5초 주기며 과거 표본을 재연결 후 몰아서 보내지 않는다.
- [ ] State는 상태 전이 즉시와 무변화 30초 주기에 발행한다.

Telemetry v2의 시스템 온도 필드는 `system.jetson_temp_c`다. CPU·GPU·모터
온도를 임의의 추가 필드로 보내면 `additionalProperties:false`로 거부된다.
`position.accuracy_m`도 v2에서 제거된 필드다. 필드를 추가해야 한다면 v2를
임의로 수정하지 말고 Major 계약 변경 절차를 따른다.

## 8. 단계별 실기 검증

### 8.1 TLS·Credential·ACL

- [ ] TLS Handshake와 Hostname 검증 성공
- [ ] 발급된 Robot username/password로 CONNACK 성공
- [ ] Presence `ONLINE` 수신
- [ ] 자기 Namespace 발행·구독 성공
- [ ] 타 Robot Namespace 발행·구독 거부
- [ ] Robot의 `commands/request` 발행 거부
- [ ] Robot의 `ingest/receipt` 발행 거부
- [ ] 인증 실패 로그에 Secret·Hash·Fingerprint 미노출

### 8.2 Presence·State·Telemetry

최소 1분 이상 연속 수신하여 다음을 대조한다.

- [ ] Telemetry 5초 주기
- [ ] State 30초 주기와 상태 전이 즉시 발행
- [ ] GPS `valid`, `lat`, `lon`, `heading_deg`
- [ ] Battery `valid`, `percent`, `state`, `source`
- [ ] `system.jetson_temp_c`
- [ ] Mission 유무에 따른 `mission_id`·`mission` Null 일관성
- [ ] `mission.elapsed_sec`의 단조 증가와 PAUSED 중 계속 증가
- [ ] History INSERT와 Current UPSERT 동시 확인
- [ ] `observedAt`·`receivedAt` 분리
- [ ] 오래된 Telemetry가 Current·지도 Marker를 되돌리지 않음
- [ ] REST 조회·SSE 알림·React 카드·지도의 값과 시각 일치

### 8.3 Mission·Command

실물 로봇을 움직이기 전에 안전한 시험 공간과 현장 정지 수단을 확보한다.

| 지원 Command | 기대 검증 |
| --- | --- |
| `START_MISSION` | Mission 시작, ACK, 최종 Result |
| `PAUSE_MISSION` | Mission 일시정지, ACK, 최종 Result |
| `RESUME_MISSION` | Mission 재개, ACK, 최종 Result |
| `CANCEL_MISSION` | Mission 종료, ACK, 최종 Result |
| `EMERGENCY_STOP` | 즉시 정지 후 Result |
| `CLEAR_EMERGENCY_STOP` | 긴급정지 해제 후 Result |

각 Command마다 다음 사슬을 동일 `command_id`로 대조한다.

```text
REST 생성→DB CREATED/SENT→commands/request→ACCEPTED
→실제 상태 전이→최종 Result→DB→SSE→React
```

- [ ] ACK `ACCEPTED`와 최종 `COMPLETED`·`FAILED`·`REJECTED`를 분리 저장·표시
- [ ] 만료 명령이 실행되지 않음
- [ ] 동일 `command_id` 재수신 시 물리 동작을 재실행하지 않음
- [ ] Mission 상태·Robot mode·Command 상태·시간 기반 진행률 일치

`RETURN_TO_CHARGE`는 v2 호환성을 위한 예약값이며 현재 관리자 제어·Backend
상태 전이·Mock E2E 범위가 아니다. Jetson은 이 명령을 지원 완료로
처리하지 말고 미지원 거부한다.

### 8.4 단절·재연결·Replay·Expiry

1. Jetson Process는 유지하고 네트워크만 단절한다.
2. LWT OFFLINE과 Backend Offline 판정을 확인한다.
3. 네트워크를 복구한다.
4. 같은 Process의 `boot_id`가 유지되는지 확인한다.
5. ONLINE Presence·최신 State·Command Topic 재구독을 확인한다.
6. Event Outbox는 기존 식별자·시각·Sequence를 유지하고 재전송하는지 확인한다.
7. Telemetry는 재연결 후 최신 표본부터 재개하고 누락분을 몰아서 보내지 않는다.
8. 만료 Event·Command·늦은 Result가 업무 상태를 되돌리지 않는지 확인한다.
9. Process를 재시작하여 새 `boot_id`와 첫 `seq=1`을 확인한다.

## 9. 팀원 B·C 책임 경계

- B는 Incident Topic이 Broker→Backend로 전달되는 통로와 MQTT Envelope까지
  확인한다.
- Incident 내용 검증, 결함 저장·병합, Image·S3는 팀원 C의 완료 판정이다.
- 충전소 자동 복귀·충전·충전 후 Mission 재개는 Day 7 범위에 포함하지 않는다.

## 10. 증거 기록 양식

각 항목을 다음 형식으로 남긴다.

```markdown
### <점검 항목>

- 실행 시각: YYYY-MM-DD HH:mm (Asia/Seoul)
- 대상: Jetson / EC2 / Backend / Browser
- 실행 명령·절차:
- 기대 결과:
- 실제 결과:
- 증거 경로:
- 상태: PASS | FAIL | WAITING
- 실패 원인·후속 담당:
```

증거에는 Robot ID, Topic, 제한된 상태·오류 코드, 시각만 남긴다. Password,
Authorization Header, Cookie, CSRF Token, Private Key, Credential 모달은 제외한다.

## 11. 최종 체크리스트

### 외부 연동

- [ ] EC2 Endpoint·8883·TLS·보안그룹
- [ ] Backend 운영 Broker 연결
- [ ] Robot ACTIVE·Credential 발급·Jetson 안전 주입
- [ ] Secret 비노출

### 실제 Robot

- [ ] ONLINE Presence·State
- [ ] UUIDv4 `boot_id`·새 Boot `seq=1`·공용 Sequence
- [ ] Telemetry 5초·State 30초·NTP/UTC
- [ ] GPS·배터리·Jetson 온도·Mission 실데이터
- [ ] History·Current·REST·SSE·React 대조

### Mission·Command

- [ ] Mission 생성·시작·일시정지·재개·종료
- [ ] 긴급정지·긴급정지 해제
- [ ] 6개 Command의 ACK·최종 Result
- [ ] REST Idempotency·MQTT `command_id` 중복 실행 방지
- [ ] Expiry·늦은 Result·오래된 Telemetry 거부

### 장애·보안

- [ ] LWT OFFLINE·재연결·재구독
- [ ] 같은 Boot 재연결·새 Process의 새 Boot
- [ ] Event Outbox·Telemetry 미축적
- [ ] 자기 Topic 허용·타 Robot·역방향 Topic 거부
- [ ] Nginx SSE 지연 없음·재연결 후 REST 정본 복구

### 기록·인계

- [ ] PASS·FAIL·WAITING을 증거와 함께 기록
- [ ] Payload 차이를 Schema 위반·설정 차이·센서 미제공으로 분리
- [ ] 변경이 있으면 관련 테스트 실행과 새 CHANGLOG 작성
- [ ] 실제 장비 결과를 반영해 `JETSON PASS`·`DAY7 DONE` 판정

## 12. 실패 빠른 분류

| 증상 | 우선 확인 |
| --- | --- |
| TCP 연결 불가 | DNS, EC2 8883 Listen, 보안그룹, 출발지 방화벽 |
| TLS 실패 | Jetson 시각, CA Bundle, Hostname, 인증서 유효기간·Chain |
| CONNACK 거부 | Robot ACTIVE, username=`mqtt_id`, Credential 버전·폐기 여부 |
| 연결 즉시 반복 해제 | 중복 `client_id` session takeover |
| 접속되지만 수신 없음 | Topic/Payload `mqtt_id`, ACL, Backend 구독, Schema 거부 로그 |
| 만료·미래 시각 거부 | NTP, UTC `Z`, `sampled_at`·`updated_at`·`expires_at` |
| 재연결 후 Command 없음 | Command Topic 재구독, Session, Message Expiry |
| DB는 갱신되나 UI는 정지 | SSE Proxy Buffering, Browser 재연결, REST 정본 재조회 |

## 13. 실행 후 필수 기록

실제 통합을 끝낸 에이전트는 실행 명령, 결과, 미통과 항목, 증거 경로,
후속 담당을 새 `CHANGLOGS/YYYY-MM-DD_HHmm_<summary>.md`에 남긴다. 문서만
작성했거나 Mock만 재실행했다면 `JETSON PASS`나 `DAY7 DONE`으로 표현하지
않는다.
