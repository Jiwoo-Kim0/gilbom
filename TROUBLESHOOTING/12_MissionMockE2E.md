# Mission 전체 Mock E2E 트러블슈팅

- 대상 Jira: `S15P11D101-379`
- 내부 작업: `DAY6-B-00-8`
- 수행일: 2026-07-30 (Asia/Seoul)
- 최종 상태: 로컬 전체 흐름 검증 완료

## 1. 목표와 완료 기준

이번 작업의 최종 목표는 Mission 화면만 Fixture로 검증하는 것이 아니라 다음 실제
로컬 경계를 하나의 자동화된 흐름으로 연결하는 것이었다.

```text
Chromium 관리자 요청
→ Backend Command 저장·MQTT Publish
→ TLS Mosquitto
→ Python Mock Robot Command 수신
→ Command Result·Mission Event·Telemetry 발행
→ Backend MQTT Consumer
→ PostgreSQL Mission·이력·Telemetry·Command 저장
→ REST·SSE
→ React Mission 화면 갱신
```

완료 판정에는 다음 조건을 사용했다.

1. Mission이 `CREATED → RUNNING → PAUSED → RUNNING → COMPLETED`로 전이한다.
2. `START_MISSION`, `PAUSE_MISSION`, `RESUME_MISSION` Command가 모두
   `COMPLETED`가 된다.
3. 완료 전 진행률은 `99.99%`를 넘지 않고 완료 Event에서 정확히 `100%`가 된다.
4. 현재 Embedded 계약에 맞게 PAUSED 중에도 벽시계 기반 `elapsed_sec`가 증가한다.
5. 상태 이력은 최초 ADMIN `CREATED` 1건과 MQTT 전이 4건, 총 5건이다.
6. Mission 지도 Snapshot에 Area, 실제 Telemetry 궤적, 현재 위치가 존재한다.
7. React 화면이 SSE를 계기로 목록·상세·이력을 다시 조회해 상태 전이를 표시한다.
8. 테스트 종료 후 임시 Backend Container와 임시 DB가 남지 않고 기존 Backend가
   정상 복구된다.

충전소 복귀, 충전, 충전 후 재개는 합의된 Mission 범위에서 제외했다.

## 2. 전체 수행 작업

### 2.1 Mock Robot Mission Event 지원

`web/tools/mock-robot/mock_robot.py`에 MQTT v2 Mission Event 발행을 추가했다.

- 발행 Topic:
  `tbs/v2/robots/{mqtt_id}/events/mission`
- 성공 Command와 Event 대응:
  - `START_MISSION` → `MISSION_STARTED`
  - `PAUSE_MISSION` → `MISSION_PAUSED`
  - `RESUME_MISSION` → `MISSION_RESUMED`
  - `CANCEL_MISSION` → `MISSION_CANCELLED`
- Payload는 `schema_version: "2.0"`, `mqtt_id`, `boot_id`, 공용 `seq`,
  UUID `event_id`, `source_component: "mission"`, 시각, `ttl_sec: 300`,
  `mission_id`를 포함한다.
- 취소·완료 같은 Terminal Event는 v2 Schema에 필요한 `result`를 포함한다.
- 같은 `command_id`가 재전송되면 기존 Command Result만 다시 발행하고 Mission
  상태 적용과 Event 발행은 반복하지 않는다.

`--mission-auto-complete-sec` 옵션도 추가했다. 값이 양수이고 Mission이 RUNNING인
동안 벽시계 누적시간이 임계값에 도달하면 마지막 비종단 Telemetry를 먼저 보내고
`MISSION_COMPLETED`, 완료 State를 차례로 발행한다. PAUSED 중 경과시간은 증가하지만
자동 완료 판정은 RUNNING에서만 수행한다.

`web/tools/mock-robot/tests/test_mock_robot_mission.py`에는 Fake Paho Client를 사용한
다음 단위 테스트 3건을 추가했다.

1. START·PAUSE·RESUME Event와 중복 Command 멱등성
2. 자동 완료 Event의 Terminal Result와 완료 State
3. CANCEL 후에도 Event가 취소 직전 `mission_id`와 Terminal Result를 유지하는지

`web/tools/mock-robot/README.md`에는 새 Topic, 자동 완료 옵션, 전체 E2E 실행법을
기록했다.

### 2.2 React 실시간 상태 갱신

`web/frontend/gilbom/src/features/mission/MissionManagePage.tsx`가
`useRobotRealtime`을 사용해 Robot SSE 수신 후 Mission 목록·선택 상세·상태 이력을
Debounce 재조회하도록 연결했다. 이 변경은 작업 도중 병합된 현재 HEAD에도 반영되어
있다.

Browser 검증이 표시 문자열에만 의존하지 않도록 다음 안정적인 상태 표식도 사용한다.

- Mission 상태 Badge: `data-status`
- 선택 상세: `data-testid="mission-detail"`
- 진행률: `data-testid="mission-detail-progress"`
- 상태 이력: `data-testid="mission-detail-history"`

### 2.3 실제 Chromium Spec

`web/frontend/gilbom/e2e/mission-live.spec.ts`를 추가했다.

- 실행기가 만든 관리자 Session Cookie로 실제 인증 상태를 구성한다.
- CSRF Token과 `Idempotency-Key`를 포함해 Command REST API를 호출한다.
- Command 저장 상태가 `COMPLETED`가 될 때까지 조회한다.
- React 화면에서 RUNNING, PAUSED, RUNNING, COMPLETED 전이를 관찰한다.
- PAUSED 중 REST Snapshot의 `elapsedSec` 증가를 확인한다.
- 완료 전 진행률 범위와 완료 후 `100.0%`를 검증한다.
- 상태 이력 5건과 ADMIN/MQTT 전이 출처를 검증한다.
- Mission Map REST에서 Area, 실제 궤적, 현재 위치를 검증한다.
- 완료 화면을 `mission-mock-completed.png`로 저장한다.

Naver 운영 Key나 외부 네트워크가 없어도 Mission 흐름 자체를 검증할 수 있도록 이
Spec에서는 Naver SDK 요청을 차단하고 기존 지도 Fallback을 사용한다. 실제 Naver
Origin·Key Smoke Test는 배포 환경의 별도 검증 범위다.

### 2.4 임시 통합 환경 실행기

`web/scripts/mission-mock-e2e.py`를 추가했다.

1. 기본값으로 현재 소스의 Backend Docker 이미지를 빌드한다.
2. 기존 Backend만 잠시 중지한다.
3. 실행마다 무작위 이름의 임시 PostgreSQL DB를 만든다.
4. 같은 `127.0.0.1:8080`에 임시 Backend를 기동해 빈 DB에 전체 Flyway를 적용한다.
5. 일회성 관리자 계정을 등록하고 MFA·Session 인증을 완료한다.
6. Robot, `robot_current_state`, Robot Assignment를 Seed한다.
7. Patrol Area와 계획시간 12초 Mission은 실제 REST API로 생성한다.
8. 현재 로컬 Broker의 Robot Test Credential과 CA를 사용해 Python Mock Robot을
   실제 TLS Mosquitto에 연결한다. Secret 값은 출력하지 않는다.
9. Robot이 ONLINE이 된 뒤 실제 Chromium Spec을 실행한다.
10. DB에서 완료 상태·진행률·이력·Telemetry 궤적·Command 3건을 다시 대조한다.
11. 성공·실패와 무관하게 `finally`에서 Mock Robot, 임시 Backend, 임시 DB를
    제거하고 기존 Backend를 다시 기동해 Health를 확인한다.

재시도 중 Backend 이미지 재빌드만 생략할 때는
`MISSION_MOCK_E2E_SKIP_BACKEND_BUILD=1`을 사용할 수 있다. 최종 판정은 이 값을
사용하지 않고 현재 소스를 다시 빌드한 실행 결과로 내렸다.

### 2.5 Vite·환경변수 결정

`web/frontend/gilbom/vite.config.ts`와 프론트 `.env`는 변경하지 않았다.

기존 Vite Proxy가 `/api`를 `127.0.0.1:8080`으로 전달하므로 실행기가 정상 Backend를
잠시 내리고 임시 Backend를 같은 주소에 기동했다. 따라서 로컬 E2E만을 위해 새
환경변수나 Proxy 변경을 추가할 필요가 없다.

EC2에서도 Frontend가 Nginx의 상대 경로 `/api`를 사용하고 Nginx가 Backend로
Reverse Proxy하는 현재 구조라면 이 로컬 실행기용 환경변수는 필요하지 않다. 단,
이 실행기는 운영 DB나 운영 Backend에서 실행하는 도구가 아니라 로컬·CI 전용이다.

### 2.6 Flyway 번호 충돌 정리

작업 중 다른 범위의 Robot Image API가
`V31__create_robot_image_api_key.sql`로 병합되면서 시간 기반 Mission Migration의
기존 V31과 버전이 중복되었다. Flyway는 내용이 달라도 같은 Version 두 개를 허용하지
않으므로 새 DB의 ApplicationContext가 시작되지 않았다.

Mission Migration을 다음처럼 V32로 이동했다.

```text
V31__add_time_based_mission_contract.sql
→ V32__add_time_based_mission_contract.sql
```

SQL 내용은 바꾸지 않았고 `web/docs/database-migrations.md`,
`web/docs/time-based-mission-contract.md`,
`web/docs/2026-07-28-story-subtask-status.md`의 버전 및 완료 상태를 함께 맞췄다.
최종 파일 스캔에서 V1~V32의 Version 중복은 없다.

## 3. 발생 문제·원인·해결

### 3.1 작업 경로와 실제 클래스 이름 오인

- 현상:
  - 작업 위치가 이미 `web`인데 `web/...`을 다시 붙여 `web/web/...`을 조회했다.
  - 존재하지 않는 `RobotCommandResultService`와 `web/mock-robot`을 먼저 찾았다.
- 원인:
  저장소의 실제 상대경로와 구현 클래스 이름을 확인하기 전에 예상 이름을 사용했다.
- 해결:
  `rg --files`와 `rg`로 실제 경로를 다시 확인했다. Mock Robot은
  `web/tools/mock-robot`, Result 수신 구현은
  `RobotCommandResultIngestionService`를 기준으로 추적했다.

### 3.2 Playwright 개발 서버 Port 4174 충돌

- 현상:
  Fixture E2E 첫 실행에서 Vite Port 4174가 이미 사용 중이라는 오류가 발생했다.
- 확인:
  실패 직후 Listener를 조회했을 때 남은 Process는 없었다.
- 원인:
  다른 Browser Test 종료와 다음 Web Server 기동 사이의 짧은 Port 해제 Race로
  판단했다.
- 해결:
  Port가 비어 있음을 확인한 뒤 같은 명령을 재실행했고 2건 모두 통과했다. 지속
  재현되지 않아 설정 변경은 하지 않았다.

### 3.3 Mock Robot이 ONLINE이 되지 않음

- 현상:
  첫 전체 실행에서 `mock_robot_online` 대기가 실패했다.
- Backend 근거:
  `Registered robot does not have a current-state row`
- 원인:
  E2E Seed에 `robot.robot`은 있었지만 Presence 처리 전제인
  `telemetry.robot_current_state` 행이 없었다.
- 해결:
  운영 Robot 등록 흐름과 같이 초기 `robot_current_state(OFFLINE)`를 함께 Seed했다.
  이후 TLS Presence가 ONLINE으로 정상 전이했다.

### 3.4 상태 이력 기대값 4건과 실제 5건 불일치

- 현상:
  Browser가 기대한 이력은 MQTT 전이 4건이었지만 API는 5건을 반환했다.
- 원인:
  Mission 생성 시 정상적으로 기록되는 ADMIN `CREATED` 이력을 누락해 계산했다.
- 해결:
  기대 순서를 `CREATED, RUNNING, PAUSED, RUNNING, COMPLETED`로 수정하고 첫 건은
  ADMIN, 나머지 네 건은 MQTT인지 별도로 검증했다.

### 3.5 DB 증적 SQL에서 예약어 Alias 사용

- 현상:
  Browser 흐름은 통과했지만 후속 DB 증적 SQL이 `command` Alias에서 실패했다.
- 원인:
  PostgreSQL 예약어와 충돌할 수 있는 Alias를 사용했다.
- 해결:
  Alias를 `robot_command`로 변경했다.

### 3.6 존재하지 않는 `robot.patrol_trajectory` 조회

- 현상:
  DB 증적 단계에서 `robot.patrol_trajectory` Relation이 없다는 오류가 발생했다.
- 원인:
  초기 역할 문서의 논리 테이블 이름을 실제 구현 테이블로 오인했다.
- 실제 정본:
  V22 계약은 중복 궤적 테이블을 만들지 않고
  `telemetry.robot_telemetry.mission_id`로 실제 궤적을 관리한다.
- 해결:
  동일 Mission의 유효 GPS Telemetry 건수를 궤적 증적으로 조회하도록 수정했다.

### 3.7 동시 병합으로 Flyway V31 중복

- 현상:
  Backend 전체 테스트 604건 중 100건이 ApplicationContext 초기화에서 실패했다.
- 공통 원인:
  `V31__create_robot_image_api_key.sql`과
  `V31__add_time_based_mission_contract.sql`이 동시에 존재했다.
- 해결:
  Mission을 V32로 이동하고 관련 문서를 수정했다.
- 재검증:
  전체 Backend 테스트는 현재 소스에서 625건, 실패 0, 오류 0, Skip 0으로
  통과했다. 파일 스캔도 총 32개 Migration Version이 모두 고유함을 확인했다.

### 3.8 최종 E2E가 이전 Backend 이미지를 사용할 가능성

- 현상:
  빠른 재시도에서는 `MISSION_MOCK_E2E_SKIP_BACKEND_BUILD=1`로 이미지를 재사용했다.
  이후 동시 병합과 V32 이동이 있었으므로 이 성공만으로 현재 소스를 증명할 수 없었다.
- 해결:
  최종 실행에서는 Skip 변수를 제거하고 `docker compose build backend`부터 다시
  수행했다. V31 Image·V32 Mission을 빈 DB에 적용한 현재 이미지로 전체 흐름이
  재통과했다.

### 3.9 정리 확인 명령의 Compose Service 이름 오인

- 현상:
  첫 정리 확인에서 `docker compose exec postgres`를 사용해
  `service "postgres" is not running`이 출력됐다. 다음 시도에서는 로컬 추정
  사용자 `gilbom`으로 조회해 Role이 없다는 오류도 발생했다.
- 원인:
  실제 Compose Service는 `db`이며 DB 계정은 Container 환경변수로 관리한다.
- 해결:
  `db` Service 안에서 `POSTGRES_USER`, `POSTGRES_PASSWORD`를 사용해 재조회했다.
  `gilbom_mission_mock_e2e_%` DB는 0건이었다. 이 오류는 테스트 자원 정리 실패가
  아니라 사후 확인 명령의 대상·계정 오인이었다.

### 3.10 PowerShell에서 `git diff --quiet` 결과를 잘못 판정

- 현상:
  최종 자체검토 Script가 `vite.config.ts unchanged: FAIL`을 출력했지만 바로 이어서
  확인한 `git status`와 `git diff`에는 해당 파일 변경이 없었다.
- 원인:
  PowerShell의 `if (git diff --quiet ...)`는 Process Exit Code가 아니라 명령의
  출력값을 조건식으로 평가했다. `--quiet` 성공은 출력이 없으므로 거짓처럼
  처리됐다.
- 해결:
  명령 실행 직후 `$LASTEXITCODE -eq 0`을 판정하도록 수정해 재확인했다.
- 결과:
  `vite.config.ts unchanged: PASS`. 실제 파일 변경이나 복원 작업은 발생하지 않았다.

## 4. 최종 검증

### 4.1 Python Mock Robot

```powershell
cd web
python -m py_compile tools/mock-robot/mock_robot.py scripts/mission-mock-e2e.py
python -m unittest discover -s tools/mock-robot/tests -v
```

- 문법 검증 통과
- Mission 단위 테스트 3건 통과
- 실패 0

### 4.2 Frontend

```powershell
cd web/frontend/gilbom
npm run lint -- --no-cache
npm run build
npm run test:e2e:mission -- --output=test-results/mission-mock-regression-final
```

- ESLint 통과
- TypeScript·Vite Production Build 통과
- 1,904 Modules 변환
- 시간 기반 Mission·Mission 지도 Fixture Chromium E2E 2건 통과

### 4.3 Backend 전체 회귀

```powershell
cd web/backend
.\gradlew.bat test --rerun-tasks
```

- Test Suite 114개
- Test 625건
- 실패 0
- 오류 0
- Skip 0
- `BUILD SUCCESSFUL`

### 4.4 현재 소스 전체 Mock E2E

```powershell
cd web
python scripts/mission-mock-e2e.py
```

최종 결과:

```text
1 passed (18.0s)
PASS: CREATED -> RUNNING -> PAUSED -> RUNNING -> COMPLETED,
progress 100, history 5, trajectory, and three completed commands
```

Playwright 결과:

```text
web/frontend/gilbom/test-results/mission-mock-live/.last-run.json
status: passed
failedTests: []
```

완료 Screenshot:

```text
web/frontend/gilbom/test-results/mission-mock-live/
  mission-live-Mission-Mock-전체-흐름이-TLS-MQTT부터-React까지-이어진다-chromium/
  mission-mock-completed.png
```

### 4.5 종료·복구

- `gilbom-backend-1`: healthy
- `gilbom-db-1`: healthy
- `gilbom-frontend-1`: healthy
- `gilbom-mosquitto-1`: 실행 중
- `gilbom-mission-mock-e2e-*` Container: 0개
- `gilbom_mission_mock_e2e_%` DB: 0개
- Flyway Migration: V1~V32 총 32개, Version 중복 0
- `git diff --check`: 통과

## 5. 영향과 주의사항

- Mock Robot은 실제 Embedded Firmware가 아니라 MQTT v2 계약을 재현하는 로컬
  통합 도구다. 실제 Jetson 배포·Broker ACL·EC2 네트워크 검증을 대체하지 않는다.
- 실제 EC2에서 `mission-mock-e2e.py`를 실행하지 않는다. 실행 중 8080 Backend를
  교체하고 임시 DB를 만들기 때문에 로컬 또는 격리 CI 환경 전용이다.
- 프론트 `.env`와 `vite.config.ts`는 이번 E2E를 위해 변경하지 않았다.
- Naver Map의 운영 Domain·`ncpKeyId` 조합은 외부 서비스 설정이므로 EC2 배포
  환경에서 별도 Smoke Test가 필요하다.
- 실제 Jetson이 동일한 Event 순서, `ttl_sec`, `mission.elapsed_sec`, 공용 `seq`,
  Command Result를 발행하는지는 Day 7~10 장비 통합에서 확인해야 한다.

## 6. 결론과 후속 작업

`DAY6-B-00-8`의 로컬 완료 기준은 충족했다. 현재 Backend·Mock Robot·MQTT v2
Mission Event·PostgreSQL·REST/SSE·React·Chromium이 하나의 자동화된 체인으로
동작한다.

남은 작업은 기능 구현이 아니라 다음 외부 환경 검증이다.

1. 실제 Jetson Python/Firmware 배포본으로 같은 시나리오 재검증
2. EC2 Broker·Backend·Nginx·Frontend 배포 환경에서 TLS·ACL·SSE 확인
3. 운영 Naver Web Service URL과 Key로 Map Tile·Overlay Smoke Test
4. 시연 전 Mock/실기 전환 절차 및 증적 Screenshot 보관
