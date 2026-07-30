# 시간 기반 Mission 계약

- 기준일: 2026-07-30
- 대상: `S15P11D101-489`, `DAY6-B-00-6`
- 상태: 구현·자동 검증 완료, 실제 로그인 Backend Browser 연동은 후속 검증

## 1. 사용자 계약

관리자는 Mission 생성 시 다음 값만 선택한다.

- 활성 Robot
- 활성 방범 Area
- 계획 수행시간 `plannedDurationSec > 0`
- 선택 사항인 예정 시작 시각

사용자는 계획 Route를 선택하지 않는다. Robot은 선택 Area 안에서 장애물 회피와
경계 추종으로 자율 순찰한다.

## 2. 내부 Route 호환

기존 DB와 API의 `patrol_route_id NOT NULL` 및 FK는 유지한다. 각 Area에는
`AUTONOMOUS_DEFAULT` Route를 하나 두고 Frontend가 Area 선택 시 그 ID를 내부
요청에 자동 포함한다.

- 기존 활성 Area: V29 Migration에서 누락된 Dummy Route를 생성한다.
- 신규 Area: Area 생성 트랜잭션에서 Dummy Route를 생성한다.
- Area 경계 변경: Dummy Geometry를 새 경계 내부로 다시 생성한다.
- Area 비활성화·보관: Dummy Route도 비활성 상태로 동기화한다.
- Mission 생성: 선택 Area 소속의 활성 `AUTONOMOUS_DEFAULT`만 허용한다.

Dummy Geometry는 DB 공간 제약을 만족시키기 위한 내부 호환 값이다. Robot 명령,
지도 Layer, 사용자 화면, 진행률 산식에 사용하지 않는다.

## 3. 시간과 진행률

계획 시간은 `robot.mission.planned_duration_sec`에 초 단위로 저장한다.
기존 Mission은 3,600초로 Backfill한다.

```text
progressPercent
= min(99.99, mission.elapsed_sec / plannedDurationSec * 100)
```

- 분자: 최신 유효 MQTT Telemetry의 `mission.elapsed_sec`
- 분모: Mission의 `plannedDurationSec`
- 적용 상태: `RUNNING`, `PAUSED`
- 오래된 Telemetry: 더 최신 표본이 있으면 진행률에 반영하지 않는다.
- 역행 값: 저장 진행률은 `GREATEST`로 단조 증가한다.
- 완료 전 상한: `99.99%`
- 완료 확정: `MISSION_COMPLETED` Event에서만 `100%`

현재 Embedded 계약에서 `mission.elapsed_sec`는 Mission 시작 후 벽시계 누적
시간이다. `PAUSED` 중에도 증가한다. 일시정지 제외 수행시간으로 바꾸려면 Embedded
누적 방식과 MQTT 의미를 함께 버전 변경해야 한다.

## 4. 조회와 화면

Mission 응답은 다음 시간 필드를 제공한다.

| 필드 | 의미 |
| --- | --- |
| `plannedDurationSec` | 계획 수행시간 |
| `elapsedSec` | 가장 큰 유효 누적 경과시간 |
| `remainingDurationSec` | `max(plannedDurationSec - elapsedSec, 0)` |
| `progressObservedAt` | 표시 중인 누적시간 표본의 `sampled_at` |
| `progressStale` | RUNNING·PAUSED에서 표본이 없거나 15초 이상 지난 상태 |
| `estimatedCompletionAt` | `progressObservedAt + remainingDurationSec` |

화면은 계획·경과·남은 시간, 진행률, ETA, 기준 시각과 갱신 지연을 표시한다.
Route 선택·필터·목록·상세 표시는 제공하지 않는다.

## 5. 검증 경계

- Backend: 실제 PostGIS Testcontainers로 V1~V29 Migration, Area별 Dummy,
  Mission 생성·조회·ETA, 시간 진행률, PAUSED 증가와 완료 Event 100%를 검증한다.
- Frontend: ESLint와 production build를 검증한다.
- Browser: 실제 Chromium에서 로그인 상태와 REST 응답을 결정적 Fixture로 제공해
  생성 폼, 내부 Dummy ID, 계획시간 Payload, 시간 표시와 Route 비노출을 검증한다.

브라우저 테스트의 REST는 결정적 Route Fixture이므로 UI 회귀에는 적합하지만,
실제 인증 세션과 실행 Backend를 통과하는 단일 Browser E2E는 아니다. 실제
Backend·DB 계약은 별도의 PostGIS 통합 테스트가 담당하며, 실제 로그인
Backend Browser 연동은 운영형 E2E 후속 항목으로 남긴다.
