# 변경 로그: Command MQTT 재연결 및 비정상 상태 E2E

- 작성 시각: 2026-07-30 18:38 (Asia/Seoul)
- 작업 유형: 수정
- 상태: 완료

## 작업 요약

Jira `S15P11D101-484`의 실패, 거절, 만료, REST/MQTT 중복, 늦은 Result를
실제 TLS Broker와 Chromium을 사용하는 E2E로 검증했다. Broker 중단 뒤 Backend를
재시작해야만 연결이 돌아오던 테스트 우회 로직을 제거하고, Paho 자동 재연결의
최대 대기 간격과 연결 Watchdog를 추가해 Backend 재시작 없이 연결이 복구되도록
수정했다.

기존 `2026-07-30_1740_command-abnormal-state-e2e.md`에 후속 과제로 남았던
REST/MQTT 중복 Browser E2E는 현재 구현과 검증이 완료됐으며, Broker 복구를 위한
Backend 재시작 우회도 더 이상 필요하지 않음을 이 로그에서 정정한다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/backend/src/main/java/com/gilbom/robot/mqtt/config/MqttProperties.java` | 수정 | `mqtt.reconnect-max-delay`를 기본 5초인 `Duration` 설정으로 추가하고 1초 이상, 정수 밀리초 범위 이하로 검증했다. |
| `web/backend/src/main/java/com/gilbom/robot/mqtt/config/MqttConnectionConfig.java` | 수정 | Paho 자동 재연결의 최대 지수 Backoff 간격을 설정값으로 제한했다. |
| `web/backend/src/main/java/com/gilbom/robot/mqtt/client/MqttReconnectWatchdog.java` | 추가 | 공유 MQTT Client가 실행 중이지만 단절된 경우 설정 간격마다 `reconnect()`를 요청해 멈춘 재연결 주기를 복구한다. |
| `web/backend/src/main/resources/application.yml` | 수정 | `MQTT_RECONNECT_MAX_DELAY` 환경변수와 기본값 `5s`를 연결했다. |
| `web/backend/src/test/java/com/gilbom/robot/mqtt/config/MqttConnectionConfigTest.java` | 추가 | 자동 재연결 활성화와 최대 재연결 간격 5,000ms 적용을 검증했다. |
| `web/backend/src/test/java/com/gilbom/robot/mqtt/config/MqttProfileConfigurationTest.java` | 수정 | 기본 5초와 환경변수 `3s` 재정의를 검증했다. |
| `web/backend/src/test/java/com/gilbom/robot/mqtt/client/MqttReconnectWatchdogTest.java` | 추가 | 단절 시 재연결 호출과 연결 상태에서의 무동작을 검증했다. |
| `web/compose.yaml` | 수정 | Backend 컨테이너에 `MQTT_RECONNECT_MAX_DELAY`를 전달했다. |
| `web/.env.example` | 수정 | 재연결 최대 간격 환경변수와 용도를 문서화했다. |
| `web/backend/.env.example` | 수정 | Backend 단독 실행용 재연결 최대 간격 예시를 추가했다. |
| `web/tools/mock-robot/mock_robot.py` | 수정 | 지정 Command의 동일 ACCEPTED·최종 Result를 각각 두 번 발행하는 중복 Result 시나리오를 추가했다. |
| `web/scripts/command-abnormal-e2e.py` | 수정 | Broker 복구 시 Backend 재시작을 제거하고 재시작 횟수 0, 자동 재연결, REST/MQTT 중복, 늦은 Result를 검증했다. 종료 시 메인 Backend를 현재 이미지로 강제 재생성한다. |
| `web/scripts/command-roundtrip-e2e.py` | 수정 | E2E 종료 시 메인 Backend가 이전 이미지로 남지 않도록 현재 이미지로 강제 재생성한다. |
| `web/frontend/gilbom/e2e/command-abnormal-live.spec.ts` | 수정 | 실패·거절·만료·중복·늦은 Result 시나리오 명칭을 명확히 하고 중복 발행 뒤에도 상세 Result가 ACK와 최종 Result 두 건뿐인지 검증했다. |
| `web/docs/팀원B/작업내용/로봇-명령-왕복과-ACK_구현내용.md` | 수정 | 비정상 E2E 완료 상태, 자동 재연결 설정법, 검증 범위와 남은 보안 E2E를 현재 구현에 맞게 갱신했다. |
| `CHANGLOGS/2026-07-30_1838_command-reconnect-abnormal-e2e.md` | 추가 | 변경 파일, 실제 검증 결과, 영향과 후속 작업을 기록했다. |

## 상세 내용

- Paho의 자동 재연결을 유지하면서 기본 최대 Backoff를 128초에서 5초로 낮췄다.
- 공유 Client가 자동 재연결을 시작하지 못하거나 멈춘 경우에도 Watchdog가 같은
  설정 간격으로 재연결을 요청한다.
- 재연결 간격은 `MQTT_RECONNECT_MAX_DELAY=3s`, `5000ms`, `1m`처럼 Spring
  Duration 형식으로 지정할 수 있다.
- Broker를 실제 중지해 `PUBLISH_FAILED`를 만든 뒤 Broker만 다시 시작했으며,
  임시 Backend의 `RestartCount=0`인 상태에서 Robot 온라인 복귀와 후속 Command
  왕복을 확인했다.
- Mock Robot은 같은 `command_id` 재수신 시 기존 `result_id`를 재발행하고
  동작을 다시 실행하지 않는다. 별도의 중복 Result 옵션은 같은 ACCEPTED와 최종
  Result를 두 번 발행해 Backend DB와 화면의 중복 제거도 확인한다.
- E2E 종료 단계는 태그가 가리키는 최신 Backend 이미지로 메인 컨테이너를
  재생성해 오래된 이미지가 개발 환경에 남지 않게 했다.

## 검증

- 실행한 명령:
  - `.\gradlew.bat test -PisolatedBuildDirectory=build-codex-command-reconnect --tests "com.gilbom.robot.mqtt.config.MqttConnectionConfigTest" --tests "com.gilbom.robot.mqtt.config.MqttProfileConfigurationTest" --tests "com.gilbom.robot.mqtt.client.MqttReconnectWatchdogTest"`
  - `.\gradlew.bat test -PisolatedBuildDirectory=build-codex-command-reconnect-full`
  - `npm run lint`
  - `npm run test`
  - `npm run build`
  - `python -m py_compile scripts/command-abnormal-e2e.py scripts/command-roundtrip-e2e.py tools/mock-robot/mock_robot.py`
  - `docker compose config -q`
  - `$env:COMMAND_ABNORMAL_E2E_SKIP_BACKEND_BUILD='1'; python -u scripts/command-abnormal-e2e.py`
  - `$env:COMMAND_ROUNDTRIP_E2E_SKIP_BACKEND_BUILD='1'; python -u scripts/command-roundtrip-e2e.py`
  - `git diff --check -- <이번 작업 파일>`
- 결과:
  - Backend 집중 테스트 7개 성공, 실패·건너뜀 0개
  - Backend 전체 테스트 714개 성공, 실패·건너뜀 0개
  - Frontend lint 성공, 단위 테스트 26개 성공, 프로덕션 빌드 성공
  - Python E2E·Mock 구문 검사와 Compose 설정 검사 성공
  - 비정상 Chromium E2E 1개 성공: `PUBLISH_FAILED`, `COMPLETED`, `FAILED`,
    `REJECTED`, `EXPIRED`, REST/MQTT 중복, 늦은 Result 보호 확인
  - Broker 복구 과정에서 임시 Backend `RestartCount=0` 확인
  - 정상 Chromium E2E 1개 성공: 여섯 Command가 각각 ACCEPTED·COMPLETED
  - E2E 정리 뒤 메인 Backend 이미지가 현재 `gilbom-backend:local` 이미지와
    일치하고 `healthy`, `RestartCount=0`임을 확인
  - 대상 파일 `git diff --check` 오류 없음
- 미실행 항목과 사유:
  - 실제 Jetson·EC2 Broker 왕복은 해당 장비와 운영 환경 연결이 없어 미실행했다.

## 영향 및 주의사항

- Broker 장애 중 Backend는 기본 최대 5초마다 재연결을 시도하므로 기존 Paho
  기본 최대 128초보다 복구가 빠르지만 장기 장애에서는 연결 시도 빈도가 늘어난다.
- 운영 환경의 Broker 부하나 네트워크 정책에 맞춰
  `MQTT_RECONNECT_MAX_DELAY`를 조정할 수 있다.
- REST API, MQTT Payload 계약과 DB Schema 변경은 없다.
- 전체 Backend 테스트 종료 시 Testcontainers 종료 순서에 따른 Hikari·Scheduler
  경고가 출력됐지만 테스트 실패는 없었다.

## 미완료 및 후속 작업

- 권한, Robot Scope, CSRF, 감사의 Browser E2E
- 실제 Jetson·EC2 Broker에서 장기 단절과 재연결 확인
