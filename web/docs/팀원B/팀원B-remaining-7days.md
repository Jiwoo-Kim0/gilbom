# 팀원 B 남은 7일 구현 계획 (2026-07-30 ~ 2026-08-05)

담당: 팀원 B — 로봇 통신 · 텔레메트리 · 실시간 관제 · 순찰 Mission · Command

이 문서는 초기 12일 계획을 현재 `master`의 실제 코드·테스트·작업 트리에 맞춰
다시 정리한 실행 문서다. 완료 판정은 과거 체크박스보다 최신 코드, 최신
`CHANGLOGS/`, 자동 테스트와 실제 E2E 결과를 우선한다.

---

> **2026-07-30 현재 긴급 정정**
>
> - MQTT v2 AsyncAPI·JSON Schema·Fixture·자동 검증은 이미 동결 완료다.
> - 시간 기반 Mission의 실제 로그인·MFA→Frontend→Backend→PostgreSQL Browser
>   E2E도 완료됐다.
> - Mission 전체 Mock E2E도 2026-07-30 14:28 Playwright 결과 기준
>   `passed`로 확인됐다. 다만 관련 Mock·Harness·Test 파일은 현재 작업 트리에
>   남아 있어 검증 재실행·CHANGLOG·커밋 정리가 필요하다.
> - Mission Migration은 현재 작업 트리에서 V31→V32로 이동됐다. V31 Image를
>   유지하고 V32 Mission을 적용하는 정리는 맞지만, 삭제·신규 파일이 아직
>   미커밋 상태이므로 빈 DB 재검증과 커밋 전에는 공식 완료가 아니다.
> - 현재 남은 기능 P0는 Command Frontend와 Command Broker/Browser E2E다.

## 0. 현재 상태 진단

### 0.1 완료한 범위

| 영역 | 상태 | 실제 근거 |
| --- | --- | --- |
| MQTT v2 계약 | `DONE` | `tbs/v2`, Payload `"2.0"`, AsyncAPI 3.1.0, JSON Schema 12개, Fixture 33개, 계약 테스트 |
| MQTT 수신·발행 기반 | `DONE (LOCAL)` | TLS Mosquitto, Spring Subscriber·Publisher, Topic parser·dispatcher, QoS·Retain·Message Expiry |
| TTL·Replay | `DONE (LOCAL)` | Event 60~300초 TTL, 미래 시각·이전 Boot·Sequence 충돌 거부, 정확한 재전송 멱등 |
| MQTT ACL·Credential | `DONE (LOCAL)` | Robot별 Topic ACL, 회전·조회·폐기, Registry Drift 탐지·복구, 로컬 TLS Cutover |
| Telemetry | `DONE (LOCAL)` | History INSERT, Current UPSERT, `observedAt`·`receivedAt`, 오래된 Current 덮어쓰기 방지 |
| Presence·State | `DONE (LOCAL)` | Boot Session, Status History, LWT, 90초 Offline·30초 Scan, commit 후 SSE |
| Robot 조회·실시간 관제 | `DONE (LOCAL)` | 목록·요약·지도·상세 REST, 객체 Scope, React Card·Marker, SSE 재연결 후 REST 정본 동기화 |
| Command Backend | `DONE` | REST·DB·MQTT Publish, ACK·Result, 상태 전이, Timeout, 멱등성, Message Expiry, 감사·Scope |
| Patrol Area·Route | `DONE` | Area 등록·변경·보관, Route 불변 버전, Area별 내부 `AUTONOMOUS_DEFAULT` |
| 시간 기반 Mission | `DONE` | 계획시간, `elapsed_sec` 진행률·ETA·Stale, 완료 전 99.99%, 완료 Event 100% |
| Mission 화면·지도 | `DONE (LOCAL)` | 생성·목록·상세·이력, Area·Trajectory·현재 위치, 이탈 경고, Chromium·실제 로그인 E2E |
| Mission 전체 Mock E2E | `DONE (LOCAL)` | 실제 TLS MQTT·Mock Robot·임시 Backend/DB·REST/SSE·Chromium, START→PAUSE→RESUME→COMPLETED 결과 `passed` |

### 0.2 현재 진행 중

| 작업 | 현재 증거 | 완료를 막는 것 |
| --- | --- | --- |
| Mission E2E 산출물 정리 | E2E 결과 `passed`, 실행기·Mock·Browser 검증 코드 존재 | 관련 소스·테스트가 작업 트리에 남아 있어 재실행·변경 로그·커밋 필요 |
| Command Frontend | Backend API·SSE Event는 존재 | `features/command/` 폴더 자체가 없음. Type·API Client·권한·Route·버튼·Modal·상태 화면 필요 |
| Command Broker/Browser E2E | Mock가 Command Request와 Result를 처리 | REST→TLS Broker→Mock→DB→SSE→React 단일 증거 없음 |
| 팀원 B 결과 문서 | 기존 DailyLog·Troubleshooting은 풍부함 | 작업별 인수인계 문서와 최신 상태판이 분산돼 있음 |

### 0.3 즉시 차단 항목

#### Flyway V31→V32 이동의 공식 반영 대기

현재 작업 트리 파일:

```text
V31__create_robot_image_api_key.sql
V32__add_time_based_mission_contract.sql
```

현재 Git 이력에서는 팀원 C 이미지 V31이 먼저 `master`에 들어왔고 Mission V31이
후속으로 병합됐다. 작업 트리의 V32 이동 방향은 맞다. 남은 절차는 다음과 같다.

1. 최신 `master`의 마지막 버전을 다시 확인한다.
2. 이미지 V31과 Mission V32만 존재하는지 확인한다.
3. Mission SQL이 이동 전과 동일한지 Blob/Hash를 확인한다.
4. `database-migrations.md`, 시간 Mission 문서, 테스트 설명을 함께 고친다.
5. 빈 PostGIS DB에서 V1~최신 전체 Migration을 실행한다.
6. 이동 전 Mission V29/V31을 적용한 로컬 DB는 재생성하거나 별도 교정한다.
   적용 이력에 단순 `repair`만 실행하면 누락된 DDL을 숨길 수 있으므로 금지한다.

이 항목이 끝나기 전에는 과거 Backend 전체 테스트나 Docker 재기동 성공을 현재
V32 정본의 공식 근거로 사용하면 안 된다.

### 0.4 문서와 실제 상태의 차이

| 기존 표기 | 현재 판정 |
| --- | --- |
| `B-STORY-08` AsyncAPI·Schema·Fixture TODO | 2026-07-30 11:45 동결 완료 |
| Mission 실제 로그인 Browser E2E 남음 | 2026-07-30 13:42 완료 |
| Mission V29 또는 V31 단일 정본 | 작업 트리 V32로 이동, 재검증·커밋 대기 |
| Mission 전체 Mock E2E 미착수 | 14:28 결과 `passed`; 산출물 정리·커밋만 남음 |

---

## 1. 작업 원칙

1. **Migration 충돌을 먼저 없앤다.** 계약·기능이 맞아도 빈 DB가 뜨지 않으면
   팀 전체 개발과 시연이 멈춘다.
2. **완료된 Backend를 다시 만들지 않는다.** Command Backend, MQTT v2,
   Telemetry·SSE와 Mission REST는 회귀 검증만 한다.
3. **남은 P0는 종단 간 사용자 흐름으로 닫는다.**
   - Mission: Area→Mission→Command→Telemetry→완료 Event→화면
   - Command: 확인 Modal→REST→MQTT→Result→DB/SSE→화면
4. **Frontend는 MQTT Broker에 직접 연결하지 않는다.** REST가 정본이고 SSE는
   변경 알림이다. SSE 재연결 후 반드시 REST를 다시 읽는다.
5. **실제 장비와 로컬 완료를 섞지 않는다.** Jetson·EC2는 `WAITING` 또는
   `DONE (LOCAL)` 후속으로 명시한다.
6. **충전소 복귀·충전·충전 후 재개는 제외한다.** MQTT 예약 enum 호환만
   유지하며 P0 완료율에 넣지 않는다.

---

## 2. 남은 7일 실행 계획

### D1 — Migration V32 검증·커밋 + Mission Mock E2E 산출물 마감

#### 1단계: Flyway 정리

- V31 Image·V32 Mission 단일 버전 확인
- 관련 문서·Test 설명 버전 갱신
- 빈 DB V1~최신 전체 적용
- 기존 로컬 DB 처리 방법 기록

완료 기준:

- 같은 Flyway Version 파일이 0개다.
- `PatrolMissionSchemaIntegrationTest`가 빈 DB에서 전체 Migration을 통과한다.
- Report·Image Migration을 포함한 전체 Backend Context가 뜬다.

#### 2단계: 완료된 Mission Mock E2E 산출물 정리

현재 작업 트리의 다음 파일을 먼저 검토한다.

```text
web/tools/mock-robot/mock_robot.py
web/tools/mock-robot/README.md
web/tools/mock-robot/tests/test_mock_robot_mission.py
web/scripts/mission-mock-e2e.py
```

이미 통과한 결과를 다음 필수 시나리오와 다시 대조한다.

1. ACTIVE Patrol Area와 내부 Dummy Route 생성
2. Robot과 짧은 계획시간 Mission 생성
3. `START_MISSION` REST 요청
4. TLS Broker에서 Mock가 Command 수신
5. `ACCEPTED`·`COMPLETED` Result 반환
6. `MISSION_STARTED` Event와 State 반영
7. Telemetry `mission.elapsed_sec` 증가
8. `PAUSE_MISSION`·`RESUME_MISSION`
9. 완료 전 진행률 최대 99.99%
10. `MISSION_COMPLETED` Event 뒤 Mission 100%
11. DB·REST·SSE·React 값 대조
12. 임시 Container·DB·계정·Robot·Mission 정리

완료 기준:

- Mock 단위 테스트와 실제 TLS Broker E2E를 재실행해 모두 통과한다.
- 같은 Command 재전달이 동작·Mission Event를 중복 실행하지 않는다.
- 마지막 비종결 Telemetry가 저장된 뒤 완료 Event가 들어온다.
- 실패해도 `finally`에서 일반 Backend와 임시 자원을 복구한다.
- 별도 작업 문서와 CHANGLOG를 남긴다.

### D2 — Command Frontend 데이터 계층·권한·Route

새 권장 구조:

```text
web/frontend/gilbom/src/features/command/
├─ commandApi.ts
├─ commandTypes.ts
├─ CommandControlPage.tsx
├─ CommandHistory.tsx
├─ CommandConfirmModal.tsx
└─ command.css
```

구현 요구:

- Type
  - `CommandType`
  - `CommandStatus`
  - 생성 요청
  - Command·Result 응답
  - 페이지 응답
- API
  - `POST /api/v1/robots/{robotId}/commands`
  - `GET /api/v1/robots/{robotId}/commands`
  - `GET /api/v1/commands/{commandId}`
  - `GET /api/v1/commands/{commandId}/results`
- Permission
  - `ROBOT_COMMAND_VIEW`
  - `ROBOT_COMMAND_EXECUTE`
- POST
  - UUID `Idempotency-Key`
  - 동일 사용자 시도의 재시도에는 같은 Key 유지
  - 새 사용자 시도에만 새 Key 생성
- Route
  - 직접 URL 접근도 Permission으로 차단
  - 범위 밖 Robot은 404와 일반 오류 화면

완료 기준:

- `features/command/`가 Backend DTO와 1:1로 맞는다.
- View 사용자와 Execute 사용자의 버튼 노출이 다르다.
- Frontend가 MQTT Client를 만들지 않는다.
- lint·TypeScript build·API Mock Test가 통과한다.

### D3 — 제어 버튼·확인 Modal·ACK 상태 화면

지원 대상:

| Command | 화면 제공 | 비고 |
| --- | :---: | --- |
| `START_MISSION` | O | READY Mission과 대상 Robot 필요 |
| `PAUSE_MISSION` | O | RUNNING Mission |
| `RESUME_MISSION` | O | PAUSED Mission |
| `CANCEL_MISSION` | O | 종료 사유 필수 |
| `EMERGENCY_STOP` | O | 위험 강조·사유 필수 |
| `CLEAR_EMERGENCY_STOP` | O | 안전 확인 문구 |
| `RETURN_TO_CHARGE` | X | 현재 구현 범위 제외 |

Modal 요구:

- 대상 Robot·Mission·현재 상태
- 명령 이름과 예상 영향
- 종료·긴급정지 사유
- 위험 명령 색상과 최종 확인
- 요청 중 닫기·중복 클릭 차단
- 응답 `commandId` 즉시 표시

상태 화면 요구:

- 진행: `CREATED`, `SENT`, `ACCEPTED`
- 성공: `COMPLETED`
- 실패: `FAILED`, `PUBLISH_FAILED`
- 거절: `REJECTED`
- 만료: `EXPIRED`
- 발행·만료·수신·완료 시각과 `reasonCode`
- `COMMAND_STATUS_CHANGED` 수신 후 REST 정본 재조회

완료 기준:

- ACK와 최종 Result를 구분한다.
- Offline 또는 잘못된 Mission 상태에서 실행 버튼이 비활성화된다.
- Frontend 검사는 UX 보조이고 Backend의 최종 거부를 정상 표시한다.
- 빠른 연속 클릭이 Command를 중복 생성하지 않는다.

### D4 — Command 전체 Broker·Browser E2E

정상 흐름:

```text
React 확인 Modal
→ POST + Idempotency-Key
→ DB CREATED/SENT
→ TLS MQTT Command Request
→ Mock Robot 수신·실행
→ ACCEPTED/COMPLETED Result
→ DB 상태·Result
→ COMMAND_STATUS_CHANGED
→ React REST 재조회
```

반드시 검증할 실패 흐름:

- Broker Publish 실패 → `PUBLISH_FAILED`
- Robot 업무 실패 → `FAILED`
- 안전 조건 미충족 → `REJECTED`
- Result 없음 → Scheduler `EXPIRED`
- 만료 후 늦은 Result가 상태를 되돌리지 않음
- 같은 REST Idempotency Key → 중복 Publish 없음
- 같은 `command_id` 재전달 → Mock 동작 재실행 없음
- 범위 밖 Robot 404
- CSRF 누락 거부
- 필수 감사 실패 시 전체 Rollback

### D5 — 실제 Jetson·EC2 1차 통합

외부팀과 시간을 먼저 확정한다.

검증 순서:

1. Jetson에 동결된 MQTT v2 Python 배포·Process 재시작
2. 운영 `mqtt_id`, Client ID, Credential 주입
3. EC2 TLS 연결과 LWT
4. Presence→State→Telemetry
5. NTP 오차와 5초 발행 주기
6. 네트워크 단절·재연결, 같은 Boot 유지
7. Outbox 재전송과 공용 `seq`
8. START·PAUSE·RESUME·CANCEL·Emergency Command
9. Result·Timeout·Message Expiry
10. 실제 GPS Marker·Trajectory·Area 이탈

완료 기준:

- 실제 Jetson과 EC2에서 Topic·Payload·Credential·ACL이 로컬과 같다.
- 운영 Secret·인증서 원문이 로그·문서·화면에 없다.
- 로컬 통과와 실기 미통과를 같은 DONE으로 표시하지 않는다.

### D6 — 안정성·시연 예외 처리

- 장시간 Telemetry 누락·지연·Burst
- Last Will과 90초 Offline 판정
- 오래된 값이 Current·Marker·진행률을 되돌리지 않음
- 저전력·과열 경고 정책 확정
  - 현재 코드는 값과 Event를 저장하지만 서버 임계치 경고 정책은 별도 확인 필요
- 다량 Trajectory 5,000개 상한과 렌더링
- SSE 끊김 후 REST 정본 복구
- Nginx `proxy_buffering off`
- Naver 운영 Origin·`ncpKeyId` Smoke Test
- EC2 Broker ACL·Registry Drift·Message Expiry 재검증

### D7 — 기능 동결·리허설·인계

- 신규 기능 추가 금지
- 시연 흐름 3회 이상
- 실제 Jetson 실패 시 Mock 전환 절차
- 테스트 Robot·Credential·Mission 데이터 정리
- 장애 시 복구 순서
- Jira 상태와 Commit SHA 연결
- 작업내용 문서·CHANGLOG·Troubleshooting 최종 링크 확인
- 치명·높은 우선순위 Bug만 수정

---

## 3. 팀원 B가 요구해야 할 것

### 3.1 Embedded팀

| 요구사항 | 이유 | 완료 증거 |
| --- | --- | --- |
| `tbs/v2`, `"schema_version":"2.0"` 사용 | v1/v2 혼용 방지 | 실제 Wire Payload |
| Process 시작 시 UUIDv4 `boot_id` | 재부팅·Replay 구분 | 재시작 전후 Payload |
| 같은 Boot에서 State·Telemetry·Event 공용 `seq` | 교차 Topic 중복 방지 | Sequence 로그 |
| 새 Boot 첫 순번 `1` | Backend 계약 | DB Boot Session |
| 재연결 시 같은 `boot_id` | 연결 끊김을 재부팅으로 오판하지 않음 | 단절·복구 E2E |
| Event 재시도 시 ID·발생시각·TTL 불변 | 중복 Side Effect 방지 | Outbox 재전송 |
| NTP 오차와 실제 발행 주기 공유 | Freshness 거부 기준 확인 | Jetson 시계·Broker 시각 |
| Command 중복 실행 방지 | QoS 1 재전달 안전성 | 같은 `command_id` 2회 |
| Mission `elapsed_sec` 의미 유지 | 진행률 계약 | PAUSED 포함 누적 확인 |

### 3.2 팀원 A

| 요구사항 | 이유 |
| --- | --- |
| EC2 Mosquitto Host·8883·TLS 인증서·보안그룹 | 실제 Broker 통합 |
| Backend·Robot Credential Secret 주입 경로 | 코드·문서에 Secret 금지 |
| Nginx SSE Buffering 비활성 확인 | 실시간 Event 지연 방지 |
| 운영 Naver Maps Origin과 Client ID | 지도 Tile·Overlay |
| Command 화면 Route의 공통 RBAC·CSRF 유지 | A 소유 Security 구조와 충돌 방지 |
| 다중 Backend 운영 여부 확정 | 현재 SSE Registry는 단일 인스턴스 메모리 |

### 3.3 팀원 C

| 요구사항 | 이유 |
| --- | --- |
| Incident `mqtt_id`·`event_id`·`mission_id`·`zone_id` 정본 유지 | B 전송 경로와 C 업무 처리 연결 |
| bbox·Image 계약 변경 시 MQTT v2 Major 절차 | 동결 파일 일부만 변경 금지 |
| 시연용 ACTIVE `patrol_area` 사용 | 유효 GPS여도 Area가 없으면 결함 생성 안 됨 |
| V31 이미지 Migration을 정본으로 유지하고 후속 번호 선점 공유 | B Mission V32와 향후 충돌 방지 |
| GPS 무효·Zone 미확정 Incident 처리 상태 공유 | 조용한 결함 0건 시연 사고 방지 |

---

## 4. 책임 경계

### 팀원 B가 책임지는 것

- MQTT Broker 연결·Topic·Payload 전송 경로
- Robot Credential·ACL
- Telemetry·Presence·State·Command Result Consumer
- Robot Current·History와 실시간 SSE
- Robot·Patrol·Mission·Command REST
- Robot·Mission·Command 관리자 화면
- Mock Robot과 통신 종단 검증

### 팀원 B가 지원만 하는 것

- Incident Event MQTT 전달 경로
- C의 결함 처리를 위한 Robot·Mission·Area FK
- A의 통계·보고서가 읽을 Mission·Area 데이터

### 팀원 B 소유가 아닌 것

- Incident에서 결함 생성·병합·위험도
- 이미지 업로드·S3·Presigned URL
- 결함 화면
- 로그인·세션·공통 Security
- AWS 배포·보고서 생성

---

## 5. 우선순위와 컷 라인

### P0

1. Flyway V32 이동 재검증·커밋
2. Mission 전체 Mock E2E 산출물·CHANGLOG·커밋
3. Command Frontend Type·API·권한·Route
4. 중요 명령 Modal·상태·ACK·Timeout 표시
5. Command TLS Broker·Browser E2E
6. 실제 Jetson·EC2 1회 왕복 또는 동일 계약의 백업 Mock 증거

### P1

- Telemetry 전용 History REST
- 저전력·과열 서버 경고 정책
- 다량 Trajectory Downsampling
- 다중 Backend SSE Fan-out
- 고급 관제 Dashboard UX

### P2 또는 제외

- 충전소 복귀·충전·충전 후 재개
- 복잡한 Mission 통계
- Route 사용자 선택 UI
- 자동 경로 최적화
- SSE Event Replay 저장소

P0가 하나라도 불안정하면 P1을 시작하지 않는다.

---

## 6. 하위 작업 완료 시 기록 방식

하위 작업 하나가 끝날 때마다 다음 네 곳을 함께 갱신한다.

1. `web/docs/팀원B/작업내용/<대표-구현-흐름>_구현내용.md`
2. `web/docs/팀원B/작업내용/README.md`
3. 이 문서의 현재 상태와 체크리스트
4. 새 `CHANGLOGS/YYYY-MM-DD_HHmm_<summary>.md`

작업 문서에는 반드시 다음을 적는다.

```markdown
# <대표 구현 흐름> 구현 내용

> 담당:
> 작업일:
> 연결 Jira:
> 최종 상태:

## 0. 3분 요약
## 1. 요구사항과 수용 기준
## 2. 무엇을 만들었나
## 3. 설계 판단과 그 이유
## 4. 검증과 재현 명령
## 5. 작업 중 겪은 함정
## 6. 미완 항목
### 내 몫
### 팀원 A
### 팀원 C
### Embedded팀
## 7. 관련 문서와 커밋
```

완료 문서가 생겼다는 사실만으로 DONE으로 바꾸지 않는다. 실제 테스트 결과,
미실행 항목, 외부 의존성, Commit SHA까지 대조한다.

---

## 7. 자주 쓰는 검증 명령

### MQTT 계약

```powershell
$env:PYTHONIOENCODING='utf-8'
python -m unittest discover -s test/mqtt_contract -p 'test_*.py' -v
```

### Backend

```powershell
cd web/backend
.\gradlew.bat test --rerun-tasks
.\gradlew.bat mqttBrokerE2E --rerun-tasks
```

### Frontend

```powershell
cd web/frontend/gilbom
npm run lint
npm run build
npm run test:e2e:mission
```

### Mission 운영형 Browser

```powershell
python web/scripts/mission-time-browser-e2e.py
```

### Mission 전체 Mock

```powershell
python web/scripts/mission-mock-e2e.py
```

### 정적 확인

```powershell
git diff --check
git status --short
```

검증 명령은 계획대로 적는 것이 아니라 실제 실행한 결과만 CHANGLOG에 기록한다.
