# 시간 기반 Mission 계약

- 기준일: 2026-08-06
- 대상: `S15P11D101-489`, `DAY6-B-00-6`
- 상태: 구현·자동 검증·실제 로그인 Backend Browser 연동 완료

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

- 기존 활성 Area: V32 Migration에서 누락된 Dummy Route를 생성한다.
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
- 관리자 수동 완료: 진행률과 무관하게 요청할 수 있으며 Backend가 Online·최신
  Telemetry·Robot Mode·Mission 일치를 확인한 뒤 `COMPLETE_MISSION`을 발행
- 자동 종료 요청: `RUNNING`의 `startedAt + plannedDurationSec`가 서버 시각에
  도달하고 동일한 Robot 상태 검사를 통과하면 최대 5초 안에
  `SYSTEM/COMPLETE_MISSION` 명령을 MQTT로 발행
- 완료 명령 Payload: 대상 `mission_id`와 자동
  `PLANNED_DURATION_REACHED` 또는 수동 `OPERATOR_COMPLETED` 사유를 포함
- 완료 확정: 로봇이 정지한 뒤 `MISSION_COMPLETED`를 발행해야
  Backend가 원인 Command를 검증·저장하고 `COMPLETED·100%`로 전이
- 로봇 완료 Event: 계획시간 전 실제 종료를 `MISSION_COMPLETED·100%`로 확정
- `PAUSED`는 자동 종료 명령 대상에서 제외하며 재개 후 이미
  계획시간이 지났으면 다음 검사에서 `COMPLETE_MISSION`을 발행
- Broker 발행 실패·ACK Timeout은 명령 이력에 표시하고 Mission을 거짓으로
  `COMPLETED`로 바꾸지 않는다.

완료 Command Result의 `COMPLETED`는 명령 실행 결과일 뿐 Mission 완료 확정이 아니다.
최종 성공 기준은 `MISSION_COMPLETED` Event와 Mission·상태 이력이 같은 DB
Transaction에 저장된 상태다. 현재 범위에는 Event 저장 Receipt와 미수신 자동 복구가
포함되지 않으므로 해당 장애는 운영 확인이 필요하다.

현재 Embedded 계약에서 `mission.elapsed_sec`는 Mission 시작 후 벽시계 누적
시간이다. `PAUSED` 중에도 증가하지만 서버 자동 종료 명령은 안전한 명시적 재개를 위해
`RUNNING`에만 적용한다. 일시정지 제외 수행시간으로 바꾸려면 Embedded 누적 방식과
MQTT 의미를 함께 버전 변경해야 한다.

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

- Backend: 실제 PostGIS Testcontainers로 전체 Migration, Area별 Dummy,
  Mission 생성·조회·ETA, 시간 진행률, 계획시간 `COMPLETE_MISSION`
  자동 발행, PAUSED 유지와 완료 Event
  100%를 검증한다.
- Frontend: ESLint와 production build를 검증한다.
- Browser Fixture: 실제 Chromium에서 로그인 상태와 REST 응답을 결정적 Fixture로
  제공해 생성 폼, 내부 Dummy ID, 계획시간 Payload, 시간 표시와 Route 비노출을
  검증한다.
- Browser Live: Docker Frontend `http://127.0.0.1:3000`에서 실제 비밀번호
  로그인·TOTP MFA를 통과한 뒤 45분 Mission을 생성하고, API
  `plannedDurationSec=2700`과 PostgreSQL `planned_duration_sec=2700`을
  대조한다. 고유 임시 계정·Robot·Area·Route·Mission·세션·감사로그는 종료 시
  정리한다.

운영형 검증은 아래 명령으로 재현한다.

```bash
python web/scripts/mission-time-browser-e2e.py
```
