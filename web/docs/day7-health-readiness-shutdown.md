# Day 7 Health·readiness·종료 정책

기준일: 2026-07-31

## 1. Backend health 계약

Health endpoint는 다음 세 그룹으로 고정한다.

| endpoint | 구성요소 | 의미 |
|---|---|---|
| `/actuator/health/liveness` | `livenessState` | JVM과 Spring context가 살아 있는가 |
| `/actuator/health/readiness` | `readinessState`, `db`, `flywayReadiness`, `storageReadiness` | 새 요청을 받아 DB transaction과 파일 저장을 수행할 수 있는가 |
| `/actuator/health/mqtt` | `mqttConnection` | 로봇 MQTT client manager가 실행 중이고 Broker에 연결됐는가 |

Liveness에는 DB, 파일 저장소, MQTT, LLM을 넣지 않는다. 외부 의존성 장애 때
정상 JVM을 계속 재시작하면 connection storm과 복구 지연만 커지기 때문이다.

Readiness는 허용 목록 방식이다. `db`가 실제 연결을 확인하고,
`flywayReadiness`는 Flyway initializer 완료 뒤에만 생성된다. Migration 실패는
application context 기동 자체를 실패시키므로 완료 전 readiness가 UP이 될 수 없다.
이 marker는 매 probe마다 schema history를 다시 읽지 않아 DB 부하를 늘리지 않는다.

`storageReadiness`는 권한 비트만 보지 않고 다음 두 volume에 1 byte 임시 파일을
실제로 생성·삭제한다.

- 승인 보고서 PDF 저장소
- 결함 이미지 저장소

따라서 잘못된 UID/GID, read-only mount, ACL, 파일시스템 오류를 실제 쓰기 전에
탐지한다. 응답에는 물리 경로나 예외 원문을 싣지 않는다.

## 2. 선택 의존성 정책

LLM은 readiness contributor가 아니다. LLM provider 장애나 정책 gate 미통과 시
`TEMPLATE` fallback을 사용하므로 Backend 전체를 트래픽에서 제외하지 않는다.
`GILBOM_HEALTH_READINESS_COMPONENTS` 기본값에도 LLM 항목은 존재하지 않는다.

MQTT는 `/actuator/health/mqtt`에서 별도로 진단하고 기본 readiness에서는 제외한다.
Broker 장애 중에도 보고서 조회·관리 등 DB 기반 기능은 제공한다. 다만 최초 stack
기동은 `depends_on: service_healthy`로 MQTT health를 통과한 뒤 Backend를 시작해
잘못된 TLS·credential 설정을 배포 시점에 잡는다.

로봇 기능이 곧 전체 서비스인 배포만 다음처럼 MQTT를 readiness에 포함한다.

```dotenv
GILBOM_HEALTH_READINESS_COMPONENTS=readinessState,db,flywayReadiness,storageReadiness,mqttConnection
```

이 설정은 Broker의 짧은 장애에도 Backend가 load balancer 대상에서 빠진다는
trade-off가 있으므로 운영 정책을 바꿀 때만 사용한다.

## 3. Frontend와 end-to-end

Nginx의 `GET /healthz`는 정적 server 자체 준비 상태만 `200 ok`로 반환한다. 이
endpoint가 Backend 장애까지 포함하면 API 장애 때 정적 오류 안내 화면조차
제공하지 못한다.

Frontend → Backend proxy는 별도 smoke에서
`GET /api/health`를 호출한다. SSE proxy, cache, 정적 bundle 계약은 Frontend
runtime image acceptance가 계속 담당한다.

## 4. 종료 계약

| 서비스 | 종료 신호·동작 | 강제 종료 전 유예 |
|---|---|---:|
| Backend | image `SIGTERM`, Spring graceful shutdown | Compose 30초 |
| Frontend | image `SIGQUIT`, Nginx graceful quit | Compose 10초 |
| PostgreSQL | 공식 image 기본 stop signal | 운영 Compose 60초 |
| Mosquitto | 공식 image 기본 `SIGTERM` | Compose 30초 |

Backend는 `server.shutdown=graceful`로 SIGTERM 뒤 새 HTTP 요청 수락을 중단하고
진행 중 요청을 마친다. Spring lifecycle phase 상한은 20초, MQTT disconnect
상한은 5초로 두어 Docker의 30초 SIGKILL 경계 전에 종료할 여유를 남긴다.

보고서 수정·승인은 하나의 `@Transactional` 경계 안에서 version/header, 승인
상태, PDF binding, audit log를 갱신한다. 승인 PDF 파일은 DB transaction rollback
callback으로 삭제한다. 통합 테스트는 update와 approve의 마지막 audit insert를
강제로 실패시켜 다음을 확인한다.

- 수정 version과 header pointer가 모두 원상 복구된다.
- 승인 상태와 PDF binding이 모두 남지 않는다.
- 생성된 PDF 파일과 승인 audit가 남지 않는다.

따라서 graceful shutdown이 진행 중 요청을 기다리는 동안 transaction은 전부
commit되거나 전부 rollback된다. `scripts/verify-backend-graceful-shutdown.sh`는
실제 in-flight 요청 중 SIGTERM을 보내 요청 종료 전 process가 내려가지 않고
30초 안에 exit code 137 없이 종료되는지 별도로 검증한다.

## 5. 자동 검증

`scripts/verify-backend-health-policy.sh`는 폐기 가능한 Compose 검증 stack에서
다음 장애 전환을 실제로 만든다.

1. MQTT 종료: `/health/mqtt`는 DOWN, 기본 readiness는 UP
2. MQTT 복구: 별도 health가 다시 UP
3. DB 종료: liveness는 UP, readiness는 DOWN
4. DB 복구: readiness가 다시 UP
5. readiness 응답에 LLM·MQTT가 기본 구성요소로 섞이지 않았는지 확인

이 스크립트는 의존 container를 의도적으로 중단하므로 운영 stack에서 직접
실행하지 않고 CI와 `verify-production-runtime.sh`의 임시 stack에서만 실행한다.

## 6. 확인 결과

| 검증 | 결과 |
|---|---|
| Health 단위·설정 계약 | `6 tests, failures=0, skipped=0` |
| 보고서 domain transaction 통합 | `13 tests, failures=0, skipped=0` |
| Backend 전체 회귀 | `755 tests, failures=0, skipped=0` |
| 운영 Compose 정적 계약 | PASS |
| MQTT DOWN → 별도 health DOWN, readiness UP | PASS |
| DB DOWN → liveness UP, readiness DOWN | PASS |
| 의존 서비스 복구 뒤 health 회복 | PASS |
| Frontend `/healthz`, API·SSE proxy | PASS |
| Nginx 실제 `SIGQUIT` 종료 | PASS, SIGKILL 없음 |
| Backend in-flight 요청 중 `SIGTERM` | PASS, 2초, SIGKILL 없음 |

운영 runtime acceptance는 임시 Secret과 자체서명 TLS를 사용한 별도 stack에서
수행했고 종료 후 container와 volume을 자동 정리했다.
