# 시간 기반 Mission 계약·화면·Browser E2E

## 1. 문서 목적

이 문서는 `S15P11D101-489`, `DAY6-B-00-6`에서 수행한 시간 기반 Mission
변경 전체를 기록한다. 오류만 정리하지 않고 계약 판단, DB Migration, Backend,
Frontend, Browser E2E, 검증 결과와 남은 경계를 한 번에 재현할 수 있게 한다.

## 2. 변경 전 문제

기존 Mission은 계획 Route Geometry에 현재 GPS를 투영해 진행률을 계산했다.
실제 Robot은 Area 안에서 장애물을 피하고 경계를 추종해 자율 순찰하므로 다음
문제가 있었다.

- Robot이 서버 Route를 주행 명령으로 소비하지 않는데도 Route 선택이 필수였다.
- GPS가 Route에서 30m 이상 벗어나거나 유효하지 않으면 시간은 흘러도 진행률이
  갱신되지 않았다.
- 화면이 Route를 계획의 핵심 값처럼 선택·필터·표시했다.
- 계획 수행시간, 경과시간, 남은 시간과 최신 기준 시각을 표시할 계약이 없었다.

## 3. 최종 계약

### 3.1 사용자 입력

- 활성 Robot
- 활성 방범 Area
- `plannedDurationSec > 0`
- 선택 사항인 예정 시작 시각

사용자는 Route를 선택하지 않는다.

### 3.2 내부 Route

기존 `patrol_route_id NOT NULL`과 FK를 유지하기 위해 Area마다 활성
`AUTONOMOUS_DEFAULT`를 하나 둔다. Frontend는 Area 선택 후 이 ID를 내부적으로
조회해 Mission 생성 Payload에 포함한다.

Dummy Route는 DB 공간 제약을 만족시키는 Area 내부 LineString이지만 다음에는
사용하지 않는다.

- Robot 명령 Payload
- 사용자 Route 선택·필터·목록·상세
- Mission 지도 Layer
- 진행률 계산

### 3.3 시간 진행률

```text
progressPercent
= min(99.99, mission.elapsed_sec / plannedDurationSec * 100)
```

- `RUNNING`, `PAUSED`에서 갱신한다.
- 현재 Embedded의 벽시계 계약에 따라 `PAUSED` 중에도 `elapsed_sec`는 증가한다.
- 더 최신 Telemetry가 있으면 오래된 표본은 반영하지 않는다.
- 저장 진행률은 `GREATEST`로 단조 증가한다.
- 완료 전 Telemetry 상한은 99.99%이다.
- `MISSION_COMPLETED` Event만 100%를 확정한다.
- GPS와 Dummy Route Geometry는 계산에 관여하지 않는다.

### 3.4 조회

- `plannedDurationSec`: 계획시간
- `elapsedSec`: 가장 큰 유효 누적 경과시간
- `remainingDurationSec`: `max(planned - elapsed, 0)`
- `progressObservedAt`: 표시 값의 Telemetry `sampled_at`
- `progressStale`: RUNNING·PAUSED에서 표본이 없거나 15초 이상 지남
- `estimatedCompletionAt`: `progressObservedAt + remainingDurationSec`

## 4. 전체 수행 내용

### 4.1 DB와 Area 수명주기

`V29__add_time_based_mission_contract.sql`에 다음을 추가했다.

- `robot.mission.planned_duration_sec INTEGER DEFAULT 3600 NOT NULL`
- `planned_duration_sec > 0` CHECK
- 기존 활성 Area 중 Dummy가 없는 Area에 `AUTONOMOUS_DEFAULT` Backfill

신규 Area 생성 트랜잭션은 Area 내부 Dummy를 함께 만든다. Area 경계가 바뀌면
Dummy Geometry를 새 경계 내부로 다시 계산하고, Area가 INACTIVE·ARCHIVED로
전이되면 Dummy도 DRAFT로 동기화한다. 일반 사용자 Route의 경계 포함 검증은
유지한다.

### 4.2 Backend 계약

- 생성·수정 DTO에 `plannedDurationSec`를 추가하고 양수를 검증했다.
- Mission 생성 INSERT와 감사 Snapshot에 계획시간을 연결했다.
- Mission 생성 시 선택 Area 소속 활성 `AUTONOMOUS_DEFAULT`만 허용했다.
- Telemetry 저장 뒤 GPS 유효 여부와 무관하게 `mission.elapsed_sec`를 진행률
  Service에 전달했다.
- PostGIS `ST_LineLocatePoint`, Route Join과 30m 조건을 제거했다.
- 조회 응답에 계획·경과·잔여·기준시각·Stale·ETA를 추가했다.
- 가장 큰 누적시간을 우선 조회해 화면 경과시간도 역행하지 않게 했다.

### 4.3 MQTT Payload 문서

Payload 필드의 이름이나 JSON 구조는 바꾸지 않았다. 기존
`mission.elapsed_sec`의 의미를 다음처럼 구체화했다.

- Mission 시작 후 벽시계 누적 초
- `0` 이상, Mission 안에서 단조 증가
- `PAUSED` 중에도 증가
- Mission이 바뀌면 새 Mission 기준
- 서버 시간 진행률의 분자

이 의미를 `final-mqtt-topic-schema-v2.md`와
`mqtt-v2/schemas/telemetry.schema.json`에 반영했다. 동결 가이드와 자동 계약
테스트도 함께 갱신해 사람용 계약과 기계 판독 계약이 다시 어긋나지 않게 했다.
따라서 Embedded가 새 필드를 추가할 필요는 없지만, 기존 필드를 위 의미로
발행해야 한다.

### 4.4 Frontend

- Route 필터와 Route 선택 UI를 제거했다.
- Area 선택 시 활성 `AUTONOMOUS_DEFAULT` ID를 내부 조회했다.
- 계획시간을 분 단위 정수로 입력받고 초 단위로 API에 보냈다.
- 목록·상세에서 Route 이름과 버전을 숨겼다.
- 계획·경과·남은 시간, 진행률, ETA, 기준 시각, 갱신 지연을 표시했다.
- Master/Detail, 생성 폼, 상태 Badge, 시간 Grid, 이력과 좁은 화면을 위한
  `mission.css`를 추가했다.

### 4.5 Browser E2E

Playwright와 실제 Chromium을 추가했다. 테스트는 다음을 사용자 조작으로
검증한다.

1. 인증 완료와 `MISSION_VIEW`, `MISSION_MANAGE` 권한
2. Mission 목록·상세의 25%, 계획 1시간, 경과 15분, 잔여 45분
3. 화면 전체에서 `계획 경로`가 노출되지 않음
4. 새 Mission 폼에서 Robot·Area·계획 45분 입력
5. 내부 `AUTONOMOUS_DEFAULT` ID 자동 선택
6. POST Payload의 `plannedDurationSec: 2700`
7. CSRF Header 포함
8. 생성 성공 알림과 새 Mission 목록 표시

Browser는 실제 Chromium 프로세스를 사용하지만 REST는 결정적 Route Fixture다.
따라서 UI와 요청 계약의 Browser 회귀는 완료했으나 실제 인증 세션·실행 Backend·
영속 DB를 하나의 Browser 흐름으로 잇는 운영형 E2E는 아니다. Backend와 DB는
별도의 실제 PostGIS Testcontainers 통합 테스트로 검증했다.

## 5. 작업 중 문제와 해결

### 5.1 경계 제외 테스트가 더 이상 실패하지 않음

- 현상: `rejectsInvalidOrRouteExcludingPatrolAreaBoundary`가 예외를 기대했지만
  성공했다.
- 원인: Fixture Route를 내부 `AUTONOMOUS_DEFAULT`로 바꾼 뒤, 경계 변경 시
  내부 Dummy는 재생성 대상으로 제외했기 때문이다.
- 해결: 테스트에서 일반 사용자 Route로 명시한 뒤, 일반 Route를 제외하는 경계가
  계속 거부되는지 검증했다.

### 5.2 PAUSED에서 바로 COMPLETED 전이가 DB에서 거부됨

- 현상: `illegal mission status transition: PAUSED -> COMPLETED`
- 원인: 상태 전이 정본은 `PAUSED -> RUNNING -> COMPLETED`만 허용한다.
- 해결: 테스트에 `MISSION_RESUMED`를 추가하고 다음 순번의
  `MISSION_COMPLETED`로 100%를 확인했다.

### 5.3 Playwright 설치 명령 제한시간

- 현상: `npx playwright install chromium`이 60초 제한을 넘겼다.
- 확인: `playwright install --list`에서 일반 Chromium과 ffmpeg 설치는 완료됨을
  확인했다.
- 후속 문제: 첫 E2E에서 `chromium_headless_shell` 실행 파일이 없었다.
- 해결: `npx playwright install chromium-headless-shell`만 별도로 실행했다.

### 5.4 중복 Heading Locator

- 현상: `순찰 임무 관리`가 Topbar `h1`과 본문 `h2` 두 개여서 Playwright
  Strict Mode가 실패했다.
- 해결: 본문 Heading을 `level: 2`로 제한했다.

### 5.5 Form Label Locator Timeout

- 현상: 실제 화면에는 Form이 보였지만 `getByLabel('Robot', {exact:true})`가
  Option 텍스트가 포함된 접근성 이름과 맞지 않아 Timeout됐다.
- 해결: 고유하고 안정적인 `#mission-create-robot`,
  `#mission-create-area`, `#mission-create-duration`을 사용했다.

### 5.6 Test Fixture Mission timestamp CHECK

- 현상: 시작 시각을 과거로 옮긴 뒤 `updated_at < created_at`이 되어
  `ck_mission_timestamps`가 실패했다.
- 해결: 시작 시각은 계획 경과 900초 전, `updated_at`은 `created_at`으로
  맞춰 시간 제약과 ETA Fixture를 동시에 만족시켰다.

### 5.7 PostgreSQL이 Java Instant 타입을 추론하지 못함

- 현상: Boot Session과 Telemetry 직접 INSERT에서
  `Can't infer the SQL type ... java.time.Instant`
- 해결: JDBC 인자를 UTC `OffsetDateTime`으로 명시 변환했다.

### 5.8 전체 회귀 종료 시 Connection refused 로그

- 현상: Testcontainers가 종료된 뒤 Spring Session Cleanup Thread가 닫힌 임시
  PostgreSQL에 연결을 시도해 Connection refused Stack Trace를 출력했다.
- 판정: 테스트 Assertion 실패가 아니라 Context 종료 순서의 비동기 Cleanup
  로그였다. 최종 Gradle Summary는 544 tests, failures 0, skipped 0,
  `BUILD SUCCESSFUL`이었다.

### 5.9 MQTT 동결 문서에 과거 Route 진행률 설명이 남아 있었음

- 현상: 같은 `final-mqtt-topic-schema-v2.md` 안에 새 시간 기반 설명과
  “계획 경로·유효 GPS 궤적으로 진행률 계산”이라는 과거 설명이 공존했다.
- 원인: `mission.elapsed_sec` 상세 절만 먼저 정정하고 문서 상단 핵심 결정과
  Mission Event 후속 설명을 함께 검색하지 않아 잔존 문구를 놓쳤다.
- 해결: 두 과거 문장을 시간 기반 계산으로 교체하고 JSON Schema 설명 및 동결
  가이드를 같은 의미로 맞췄다.
- 재발 방지: 계약 테스트가 JSON Schema와 사람용 계약에 `벽시계`, `PAUSED`,
  `단조 증가`, 시간 비율 산식이 모두 존재하며 과거 Route 문구가 없는지 검사한다.

### 5.10 전용 Jira 발급 전 상위 Mission Story에 연결되어 있었음

- 현상: 시간 기반 Mission 작업 문서가 범용 Mission Story
  `S15P11D101-379`를 대상으로 기록하고 있었다.
- 원인: 구현 시점에는 시간 계약·화면·Browser E2E 전용 Jira가 없어 기존
  Mission·Patrol Story 아래의 `DAY6-B-00-6`으로 추적했다.
- 해결: 전용 Jira `S15P11D101-489` 발급 후 현재 DailyLog, 계약 문서와 이
  트러블슈팅의 연결 번호를 변경했다.
- 범위 분리: 지도 작업은 별도 Jira `S15P11D101-471`과
  `DAY6-B-00-7`에 연결해 시간 계약 작업의 완료율과 섞지 않는다.
- 주의: 이미 작성이 끝난 과거 CHANGLOG는 당시 사실을 보존하기 위해 덮어쓰지
  않고 신규 Jira 연결 변경 로그를 별도로 남긴다.

## 6. 검증 결과

```text
Mission 집중 Backend
tests=29, failures=0, skipped=0

Backend 전체
tests=544, failures=0, skipped=0

Frontend
eslint: PASS
tsc -b + vite build: PASS
Vite transformed modules: 1,894

Browser
Playwright Chromium: 1 passed

OpenAPI
YAML parse + plannedDurationSec assertions: PASS

MQTT v2 동결 계약
tests=9, failures=0
JSON Schema + 사람용 계약 의미 정합성: PASS

Whitespace
git diff --check: PASS
```

실행 명령:

```powershell
cd web/backend
.\gradlew.bat test --tests "com.gilbom.robot.controller.MissionControllerTest" `
  --tests "com.gilbom.robot.integration.MissionManagementIntegrationTest" `
  --tests "com.gilbom.robot.integration.RobotEventMissionDefectIntegrationTest"
.\gradlew.bat test

cd ../..
$env:PYTHONIOENCODING='utf-8'
python -m unittest discover -s test/mqtt_contract -p 'test_*.py' -v

cd ../frontend/gilbom
npm run lint
npm run build
npm run test:e2e:mission
```

## 7. 재현·점검 순서

1. 빈 PostGIS DB에 V1부터 V29까지 Migration을 적용한다.
2. 기존 활성 Area의 `AUTONOMOUS_DEFAULT`가 Area별 하나인지 확인한다.
3. 새 Area를 생성하고 내부 Dummy가 같은 트랜잭션에서 생기는지 확인한다.
4. Robot·Area·양수 계획시간으로 Mission을 생성한다.
5. GPS가 없거나 Dummy에서 벗어난 Telemetry와 함께 `elapsed_sec`를 증가시킨다.
6. RUNNING과 PAUSED에서 진행률이 단조 증가하는지 확인한다.
7. 계획시간 이상에서도 완료 Event 전 99.99%인지 확인한다.
8. `MISSION_COMPLETED` 뒤 상태와 진행률이 100%인지 확인한다.
9. 화면에 Route가 없고 시간·ETA·Stale가 표시되는지 확인한다.
10. 운영형 E2E에서는 실제 로그인 세션과 실행 Backend로 같은 흐름을 재검증한다.

## 8. 남은 작업

- `DAY6-B-00-6`: 실제 로그인 세션·실행 Backend·영속 DB Browser 단일 체인
- `S15P11D101-471`, `DAY6-B-00-7`: Area·실제 Trajectory·현재 위치 지도와
  영역 이탈
- `DAY6-B-00-8`: MQTT→Command→Telemetry→완료 전체 Mock E2E

## 9. 실제 로그인·Backend·PostgreSQL Browser E2E 마감

### 9.1 기존 진행률이 90%였던 원인

기존 `mission-time.spec.ts`는 실제 Chromium에서 화면을 실행했지만 인증 상태와
REST 응답을 Playwright Route Fixture로 제공했다. 따라서 화면·Payload Adapter
회귀는 검증했어도 다음 운영형 경계는 통과하지 않았다.

```text
실제 비밀번호 로그인
→ 실제 TOTP MFA
→ Docker Frontend :3000
→ Nginx /api Proxy
→ 실행 중인 Spring Backend :8080
→ PostgreSQL Mission INSERT
```

구현 항목은 끝났지만 위 단일 체인의 증거가 없었으므로 완료 기준을 90%로
보수적으로 유지했다.

### 9.2 추가한 운영형 E2E

- `web/frontend/gilbom/playwright.live.config.ts`
  - Docker Frontend `http://127.0.0.1:3000`을 기본 URL로 사용한다.
  - 개발 서버와 API Fixture를 시작하지 않는다.
- `web/frontend/gilbom/e2e/mission-time-live.spec.ts`
  - 실제 이메일·비밀번호로 로그인한다.
  - RFC 6238 TOTP를 생성해 실제 MFA를 통과한다.
  - Mission 화면에서 임시 Robot·Area를 선택하고 계획시간 45분을 입력한다.
  - 실제 POST 요청의 내부 Dummy Route와 `plannedDurationSec=2700`을 검증한다.
  - 201 응답의 시간 필드·상태·진행률과 화면에 표시된 Mission Code를 검증한다.
- `web/scripts/mission-time-browser-e2e.py`
  - 고유 임시 계정·Robot·`robot_current_state`·Area·Dummy Route를 준비한다.
  - Backend API로 TOTP를 등록한 후 Playwright를 실행한다.
  - 생성 응답과 PostgreSQL `planned_duration_sec=2700`을 대조한다.
  - 성공·실패와 무관하게 Mission 이력·Mission·Robot Scope·Current State·
    Route·Area·Robot·Session·Audit·계정을 정리한다.
  - cleanup이 실패하면 Browser 검증이 통과했어도 전체 명령을 실패로 반환한다.

### 9.3 실행 중 발견한 문제·원인·해결

#### Docker 재빌드 첫 호출 중단

- 현상: 첫 `docker compose up -d --build backend frontend` 호출이 약 1초 후
  timeout으로 중단됐다.
- 원인: 명령 실행 제한을 `1000ms`로 잘못 지정했다.
- 해결: 충분한 실행 제한으로 재실행했다. 최신 이미지 빌드와 Container 재생성이
  끝났고 Backend 전체 546개 테스트가 실패 0으로 통과했다.

#### Robot 선택 목록에 임시 Robot이 없음

- 현상: 실제 로그인과 Mission 화면 진입은 성공했지만 Robot Select에 임시
  Robot Option이 없어 30초 timeout이 발생했다.
- 원인: `RobotQueryService` 목록 본문은 `telemetry.robot_current_state`를
  Inner Join한다. 운영 Robot 등록 서비스는 Robot과 Current State를 함께
  생성하지만 초기 E2E Seed는 `robot.robot`만 직접 추가했다.
- 해결: 운영 등록 불변식과 동일하게 임시 `robot_current_state(OFFLINE)`를
  Seed하고 cleanup에도 포함했다.
- 구분: 제품 등록 흐름의 결함이 아니라 테스트 Seed가 운영 등록 불변식을
  누락한 문제였다.

#### Playwright Response matcher 오용

- 현상: Area별 Route 실제 응답은 HTTP 200이었으나 `toBeOK()` 호출에서
  테스트가 실패했다.
- 원인: `page.waitForResponse()`는 Browser `Response`를 반환하지만
  `toBeOK()`는 Playwright `APIResponse` 전용 matcher다.
- 해결: Browser Response의 `status()`를 직접 200과 비교했다.

#### Append-only 이력 때문에 cleanup 실패

- 현상: Browser·Backend·DB 대조는 통과했지만
  `mission_status_history is append-only`로 임시 데이터 정리가 실패했다.
- 원인: 운영 감사 요구에 따라 Mission 이력과 Audit Log의 UPDATE·DELETE
  Trigger가 활성화되어 있다. 초기 cleanup은 Audit Trigger만 고려했다.
  또한 `try` 내부의 성공 `return` 때문에 `finally` cleanup 실패가 종료코드
  0을 바꾸지 못했다.
- 해결: DB Owner로 정확한 임시 사용자 범위 안에서 두 Append-only Trigger를
  Transaction 동안만 비활성화하고 즉시 복구했다. 반환값은 cleanup 결과까지
  반영하도록 변경했다. 실패 실행에서 남은 1세트도 제거하고 0건을 확인했다.

#### 지도 로딩 상태와 기존 시간 E2E Selector 충돌

- 현상: 운영형 E2E 완료 후 기존 Mission Fixture 2개를 함께 실행하자 시간
  Mission 테스트의 `getByRole('status')`가 생성 알림과 지도 상세 로딩 상태
  두 요소를 찾아 Strict Mode에서 실패했다.
- 원인: 지도 기능 추가 후 동일 ARIA Role을 쓰는 상태 요소가 하나 늘었지만
  시간 E2E Selector가 Role 하나만 존재한다고 가정했다.
- 해결: `MIS-TIME-002 임무를 생성했습니다.` 문구를 가진 Status만 선택하도록
  범위를 좁혔다. 시간·지도 Chromium E2E 2개가 함께 통과했다.

### 9.4 최종 검증 결과

- 최신 Docker 재빌드:
  - Frontend lint·production build 통과
  - Backend 전체 `546`개, 실패 `0`, Skip `99`
  - Backend·Frontend Container Health 정상
- `npm run lint`: 통과
- `npm run build`: 통과
- `npm run test:e2e:mission`: Chromium `2 passed`
- `python web/scripts/mission-time-browser-e2e.py`:
  - 실제 로그인·TOTP MFA 통과
  - Docker Frontend 3000→Backend 실제 API 통과
  - 45분 Mission 201 생성
  - API `plannedDurationSec=2700` 확인
  - PostgreSQL `planned_duration_sec=2700`, `CREATED`, `0.00%` 확인
  - 임시 데이터 cleanup 완료
- 잔존 데이터 확인:
  - 임시 계정 `0`
  - 임시 Robot `0`
  - 임시 Area `0`
  - 임시 Mission `0`

이번 운영형 E2E는 Mission REST 계약만 사용하므로 MQTT Payload를 변경하지 않았다.
따라서 `web/docs/final-mqtt-topic-schema-v2.md`와 MQTT JSON Schema에는 추가 변경이
필요하지 않다.

현재 `S15P11D101-489`, `DAY6-B-00-6` 판정은 완료 기준 100%, `DONE`이다.
Command·MQTT·Telemetry·완료 Event까지 잇는 전체 Mission 시나리오는 별도
`DAY6-B-00-8` 범위다.
