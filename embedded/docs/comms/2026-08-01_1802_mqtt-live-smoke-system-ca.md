# 변경 로그: 실브로커 스모크 CLI 추가 + ca_cert "system" 지원

- 작성 시각: 2026-08-01 18:02 (Asia/Seoul)
- 작업 유형: 기능 + 도구 + 문서
- 상태: 완료 (운영 E2E 는 Credential 대기)

## 작업 요약

예전 목 로봇(`test/통신테스트/mqtt_tls_test.py`)을 현행 계약과 대조한 결과
payload 쪽은 가져올 것이 없었고(목이 오히려 구식), 실제 격차는 TLS 검증
방식이었다. 운영 브로커가 Let's Encrypt 인증서로 바뀌어 CA 파일 방식으로는
접속할 수 없으므로 `ca_cert: system`(OS 신뢰 저장소 검증)을 추가했고,
목을 대체하는 실브로커 스모크 CLI 를 실제 comms 스택 위에 만들었다.

## 목 로봇이 폐기 대상인 이유

목은 07-27 임시 브로커(125.137.58.131) 시절 payload 를 손으로 조립했다.
서버가 07-28 `robot_id`(DB PK)와 `mqtt_id`(외부 통신 키)를 분리한 뒤로는
아래 세 가지가 전부 현행 서버 파서에서 거부된다
(`FAIL_ON_UNKNOWN_PROPERTIES` + Bean Validation).

| 목이 보내는 것 | 현행 서버 요구 | 거부 코드 |
| --- | --- | --- |
| 식별자 `robot_id` | `mqtt_id` 필수, 미지 필드 금지 | `MQTT_PAYLOAD_MALFORMED` |
| event 에 `ttl_sec` 없음 | `@NotNull @Min(60) @Max(300)` | `MQTT_PAYLOAD_VALIDATION_FAILED` |
| `zone_id: "ZONE-TEST-01"` | 패턴 `^[A-Z]-[0-9]{2}$` | `MQTT_PAYLOAD_VALIDATION_FAILED` |

현재 `comms/` 구현(`mqtt_id`·`ttl_sec`·zone 패턴 경고)은 동결 스키마
(`web/docs/mqtt-v2/schemas/*.schema.json`)와 필드 단위로 일치함을 확인했다 —
payload 수정은 없었다.

## 브로커 상태 (2026-08-01 실측)

| 브로커 | 상태 | 인증서 |
| --- | --- | --- |
| `125.137.58.131:8883` (07-27 임시) | **죽음** — TCP 연결 자체 불가 | 사설 CA (`local-ca-cert.pem`) |
| `i15d101.p.ssafy.io:8883` (운영) | 가동 중, TLSv1.3 | Let's Encrypt `*.p.ssafy.io` (만료 2026-09-21) |

목 로봇이 "최근까지 통신하다 끊긴" 원인은 임시 브로커 사망이다.
운영 브로커는 공인 인증서라서 CA 파일이 필요 없고, 예전 사설 CA 파일을
주면 오히려 검증에 실패한다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `embedded/comms/config.py` | 수정 | `CA_SYSTEM = "system"` 센티널 추가. `_resolve_path` 가 `"system"` 을 경로로 풀지 않게 함 |
| `embedded/comms/client.py` | 수정 | `_build_paho_client` 가 `ca_cert == "system"` 이면 `tls_set(ca_certs=None)` — paho 가 OS 신뢰 저장소를 쓴다 |
| `embedded/config.yaml` | 수정 | `ca_cert` 기본값 `gilbom-mqtt-ca.crt`(저장소에 없는 파일) → `system`. 주석 갱신 |
| `embedded/scripts/orincar.env.example` | 수정 | `MQTT_CA_CERT` 를 비움(기본 system). 사설 CA 브로커에서만 파일 경로를 주도록 안내 |
| `embedded/scripts/mqtt_live_smoke.py` | 추가 | 실브로커 스모크 CLI (아래 참조) |
| `embedded/scripts/README.md` | 수정 | §3 에 "로봇 없이 통신만 스모크" 사용법 추가, 목 로봇 폐기 경고 |
| `embedded/test/test_config.py` | 수정 | `"system"` 이 경로로 풀리지 않는 것 검증 추가, repo config 기본값 assert 를 `system` 으로 갱신 |

## mqtt_live_smoke.py

하드웨어 없이(개발 PC 에서도) 실브로커에 통신 계층만 검증하는 도구.
payload 를 직접 만들지 않고 `MessageFactory`·`MqttClient` 를 그대로 쓰므로
여기서 통과하면 `main.py` 도 같은 wire 를 탄다.

```bash
python scripts/mqtt_live_smoke.py                              # 운영 브로커 (system CA)
python scripts/mqtt_live_smoke.py --kind incident --count 3 --interval 2
python scripts/mqtt_live_smoke.py --host 127.0.0.1 --ca-cert local-ca.pem  # 사설 CA
```

- 접속값: 인자 > 환경변수 > `.env`(스크립트 옆). `MQTT_HOST` · `MQTT_ID` ·
  `MQTT_PASSWORD`(없으면 프롬프트) · `MQTT_CA_CERT`(없으면 system)
- presence ONLINE/OFFLINE 은 접속·정상종료에 자동 발행, `--kind` 로
  state · telemetry · 4종 event · command-result 선택 발행
- event 는 PUBACK 수신(Outbox 드레인)까지 확인해야 사이클 성공으로 판정
- `commands/request` 수신 시 출력만 한다 — 실행·result 응답은 `main.py` +
  `commands.CommandRunner` 경로 몫
- client_id 는 항상 랜덤 접미사 — 공유 계정에서 session taken over 방지
- Outbox 는 임시 디렉터리 — 로봇 본체의 `var/outbox.sqlite3` 를 건드리지 않음

## 검증

- 실행한 명령과 결과:
  - `pytest test/test_config.py test/test_client.py test/test_factory.py` — 151개 통과, 실패 0
  - 스모크 dry-run — 발행 7종 payload 전부 `mqtt_id` 포함·`robot_id` 없음·
    event `ttl_sec` 60~300 확인, 키 구성이 동결 스키마 required 목록과 일치
  - 운영 브로커 실접속 — system CA 로 TLS 성공(TLSv1.3), CONNACK
    **Not authorized**: `robot-test` 는 임시 브로커 전용 계정이라 운영에 없음.
    TLS·전송 계층 검증은 통과, 인증만 Credential 발급 대기
- 미실행: 운영 계정으로의 발행 왕복(Credential 없음), Jetson 실기

## 영향 및 주의사항

- Wire 계약(topic·payload·QoS·retain)은 변경하지 않았다.
- `MQTT_CA_CERT` 를 예전 사설 CA 경로로 주입해 둔 환경은 운영 브로커 접속이
  **실패**한다 — 값을 지우거나 `system` 으로 바꿔야 한다.
- 서버가 거부한 메시지는 로봇이 알 수 없다(브로커 PUBACK 으로 Outbox 정리).
  통합 기간에는 서버 `gilbom.mqtt.inbound.rejected` 메트릭을 같이 본다.
- `test/통신테스트/mqtt_tls_test.py` 와 `gilbom-mqtt-external-test/` 패키지는
  더 쓰지 말 것 — 계약 구식 + 대상 브로커 사망.

## 미완료 및 후속 작업

1. 웹 admin 에서 로봇 등록 → MQTT Credential 발급 → 스모크로 운영 왕복 검증.
2. 검증되면 Jetson 에 같은 값 주입 후 `main.py` 실기 E2E (Day 7 runbook 순서).
