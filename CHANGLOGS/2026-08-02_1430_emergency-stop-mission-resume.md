# 변경 로그: 긴급정지 후 Mission 재개 복구

- 작성 시각: 2026-08-02 14:30 (Asia/Seoul)
- 작업 유형: 수정 | 테스트 | 문서
- 상태: 부분 완료

## 작업 요약

실행 중 Mission에서 긴급정지하면 Robot만 `SAFE_STOP`으로 바뀌고 Mission 상태는
`RUNNING`에 남아, 긴급정지 해제 후 `RESUME_MISSION`을 만들 수 없던 Mock 통합 흐름을
수정했다. 긴급정지는 활성 Mission 식별자를 유지하면서 Mission을 `PAUSED`로 전환하고
`MISSION_PAUSED` Event를 한 번 발행한다. 긴급정지 해제는 자동 주행하지 않고
`PAUSED`를 유지하며, 별도 재개 명령이 성공해야 `RUNNING`으로 돌아간다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/tools/mock-robot/mock_robot.py` | 수정 | 실행 중 긴급정지 시 Mission을 `PAUSED`로 바꾸고 `MISSION_PAUSED`를 발행한다. `SAFE_STOP` 중 직접 재개는 `SAFETY_CONDITION_NOT_CLEARED`로 거절하며, 해제 후 Mission을 `PAUSED`로 유지하고 명시적 재개만 `RUNNING`으로 전환한다. |
| `web/tools/mock-robot/tests/test_mock_robot_mission.py` | 수정 | START→긴급정지→직접 재개 거절→긴급정지 해제→재개 시 Mission ID 보존, Mode·Mission 상태, Mission Event 순서를 검증하는 회귀 테스트를 추가했다. |
| `web/frontend/gilbom/e2e/command-roundtrip-live.spec.ts` | 수정 | 기존 6종 정상 명령 시나리오에 긴급정지 해제 후 두 번째 `RESUME_MISSION`을 추가해 7회 명령 왕복으로 확장했다. |
| `web/scripts/command-roundtrip-e2e.py` | 수정 | 7회 Command·Result 기대 건수와 실행 안내를 갱신했다. Command Type은 6종이며 재개가 두 번 실행된다. |
| `web/tools/mock-robot/README.md` | 수정 | 긴급정지 시 `SAFE_STOP`·`PAUSED`, 해제 후 `PAUSED` 유지, 별도 재개 후 `PATROLLING`·`RUNNING` 전환 규칙을 기록했다. |
| `web/docs/final-mqtt-topic-schema-v2.md` | 수정 | 활성 Mission 긴급정지의 `MISSION_PAUSED` 단일 발행과 해제 후 명시적 재개 계약을 추가했다. 기존 작업 중이던 `zone_id` 불투명 문자열·형식 제약 제거 변경은 수정하거나 되돌리지 않았다. |
| `web/docs/팀원B/작업내용/로봇-명령-왕복과-ACK_구현내용.md` | 수정 | 정상 Broker E2E를 6종 7회 명령으로 설명하고 긴급정지 복구 중간 상태와 최종 상태 검증 범위를 반영했다. |
| `CHANGLOGS/2026-08-02_1430_emergency-stop-mission-resume.md` | 추가 | 긴급정지 복구 변경, 검증 결과, 전체 E2E 환경 제약과 실기 후속 작업을 기록했다. |

## 상세 내용

- 정상 흐름은 `RUNNING → EMERGENCY_STOP → SAFE_STOP/PAUSED →
  CLEAR_EMERGENCY_STOP → PAUSED → RESUME_MISSION → PATROLLING/RUNNING`이다.
- `SAFE_STOP` 상태에서 해제 없이 들어온 재개는 Robot 자체에서도 거절한다. Frontend
  버튼 정책만으로 안전을 보장하지 않는다.
- 이미 `PAUSED`인 Mission에서 긴급정지하면 중복 `MISSION_PAUSED` Event를 발행하지
  않는다.
- 긴급정지 해제는 안전 잠금만 해제하며 Mission을 자동 재개하지 않는다.
- Patrol Area의 `zoneId`/`areaCode`는 형식 제약 없는 불투명 문자열이라는 현재 변경
  방향을 유지했으며, 이번 긴급정지 작업에서는 관련 검증·매핑을 변경하지 않았다.

## 검증

- 실행한 명령:
  - `python -m unittest discover -s tests -p 'test_mock_robot_mission.py' -v`
  - `node --experimental-strip-types --test test/commandPolicy.test.ts`
  - `npm run lint -- --no-cache`
  - `npm test`
  - 테스트용 공개 지도 Client ID를 프로세스 범위로만 설정한 `npm run build`
  - `git diff --check` 대상 파일 검사
  - `python scripts/command-roundtrip-e2e.py` 전체 왕복 시도
- 결과:
  - Mock Robot Mission 테스트 8건 통과. 신규 긴급정지 복구 테스트 포함.
  - Frontend Command 정책 테스트 4건 통과.
  - Frontend 전체 Node 테스트 38건 통과, 실패·Skip 0.
  - ESLint 통과.
  - TypeScript와 Vite Production Build 통과. 500 kB 초과 Chunk 경고만 발생.
  - 대상 파일 `git diff --check` 통과.
  - 전체 E2E 첫 시도는 `VITE_NAVER_MAP_CLIENT_ID` 미설정으로 Compose 해석 전에 중단됐다.
  - 공개 지도 Client ID 임시값을 프로세스 범위로 설정한 두 번째 시도는 실행 중인 DB
    Container가 없어 `database_container` 사전조건에서 중단됐다. 당시 Compose에는
    Frontend만 Healthy였고 E2E가 중지·생성한 서비스는 없다.
- 미실행 항목과 사유:
  - React→REST→TLS MQTT→Mock Robot→DB/SSE 전체 7회 명령 왕복은 로컬 PostgreSQL과
    Mosquitto가 실행 중이지 않아 완료하지 못했다.
  - 실제 Jetson 긴급정지 복구는 외부 장비가 수정된 MQTT 계약대로
    `MISSION_PAUSED`를 발행하는지 확인해야 하므로 미실행했다.

## 영향 및 주의사항

- 이 변경은 Mock Robot과 계약·회귀 시나리오를 고친다. 실제 Jetson도 실행 중
  긴급정지 시 같은 Mission ID의 `MISSION_PAUSED`를 한 번 발행해야 Backend Mission
  정본이 `PAUSED`가 되고 재개 Command가 허용된다.
- 긴급정지 해제와 임무 재개는 별도 관리자 동작이다. 해제 직후 자동 주행하지 않는다.
- `zoneId` 형식 제약 제거와 관련된 기존 미커밋 변경은 이번 작업 범위가 아니며 그대로
  보존했다.

## 미완료 및 후속 작업

- 로컬 PostgreSQL·Mosquitto가 준비된 환경에서 `python scripts/command-roundtrip-e2e.py`
  를 재실행해 7개 Command, ACCEPTED·COMPLETED 각 7건과 최종 상태를 확인한다.
- 실제 Jetson에서 긴급정지 시 `SAFE_STOP` State와 `MISSION_PAUSED` Event, 해제 후
  `PAUSED`, 재개 후 `MISSION_RESUMED`를 순서대로 대조한다.
