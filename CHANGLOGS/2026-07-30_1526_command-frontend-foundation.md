# 변경 로그: Command Frontend 기반과 로봇 상세 Route

- 작성 시각: 2026-07-30 15:26 (Asia/Seoul)
- 작업 유형: 기능
- 상태: 완료

## 작업 요약

Robot Command Backend·OpenAPI 계약에 맞춰 Command Type과 REST API Client를
추가하고 `ROBOT_COMMAND_VIEW`, `ROBOT_COMMAND_EXECUTE` 프론트 권한을 등록했다.
제어 진입점은 전역 Command 메뉴가 아니라 로봇 객체 범위가 먼저 확인되는
`/robots/{robotId}` 상세 Route로 구성했다. 상세 화면 안에서 조회 권한이 있는
사용자만 명령 영역을 보고, 실행 권한 보유 여부를 별도로 구분한다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/frontend/gilbom/src/features/command/commandTypes.ts` | 추가 | Backend DTO와 OpenAPI에 맞춘 `CommandType`, `CommandStatus`, 생성 요청, 명령·Result 응답, Page 응답 Type을 정의하고 범위 제외 명령은 추가하지 않았다. |
| `web/frontend/gilbom/src/features/command/commandApi.ts` | 추가 | 명령 생성·로봇별 목록·단건·Result REST Client와 사용자 시도 단위 UUID `Idempotency-Key` 유지 정책을 구현했다. 멱등 키로 보호되는 POST는 공통 Client의 CSRF 1회 재시도를 허용한다. |
| `web/frontend/gilbom/src/features/command/CommandControlPage.tsx` | 추가 | 로봇 상세 내부에서 명령 목록 정본을 조회하고 VIEW 사용자와 EXECUTE 사용자의 접근 수준을 구분하는 Command 기반 영역을 구현했다. 범위 밖 404는 대상 존재 여부를 드러내지 않는 일반 메시지로 처리한다. |
| `web/frontend/gilbom/src/features/command/command.css` | 추가 | 로봇 상세 내부 Command 영역, 접근 수준 Badge, API 연결 요약과 반응형 배치를 정의했다. |
| `web/frontend/gilbom/src/features/auth/permissions.ts` | 수정 | `ROBOT_COMMAND_VIEW`, `ROBOT_COMMAND_EXECUTE` 상수와 `FrontendPermission` Union을 추가했다. |
| `web/frontend/gilbom/src/app/router.tsx` | 수정 | `ROBOT_STATUS_VIEW`로 보호되는 `/robots/:robotId` 상세 Route를 등록했다. Command는 별도 전역 Route로 노출하지 않고 상세 화면 내부에서 추가 권한을 검사한다. |
| `web/frontend/gilbom/src/features/robot/RobotDetailPage.tsx` | 추가 | 권한 범위 내 로봇 단건 REST 정본과 주요 현재 상태를 표시하고 `ROBOT_COMMAND_VIEW` 보유자에게만 Command 영역을 조합하는 상세 화면을 구현했다. |
| `web/frontend/gilbom/src/features/robot/RobotFleetGrid.tsx` | 수정 | 각 로봇 카드에 `/robots/{robotId}`로 이동하는 `상세·제어` 진입 링크를 추가했다. |
| `web/frontend/gilbom/src/features/robot/robotApi.ts` | 수정 | `GET /api/v1/robots/{robotId}` 단건 조회 Client를 추가했다. |
| `web/frontend/gilbom/src/features/robot/robotTypes.ts` | 수정 | Backend `RobotDetailResponse`와 일치하는 `RobotDetail` Type을 추가했다. |
| `web/frontend/gilbom/src/app/robot/css/robot.css` | 수정 | 로봇 상세 상태 카드, 상태 Grid, 상세·제어 링크와 모바일 배치를 추가했다. |
| `web/frontend/gilbom/test/commandApi.test.ts` | 추가 | 멱등 키 수명주기, 실패 뒤 동일 키 재사용, CSRF 헤더, 명령 REST 네 Endpoint와 Page Query를 검증한다. |
| `CHANGLOGS/2026-07-30_1526_command-frontend-foundation.md` | 추가 | 구현 범위, 변경 파일, 검증 결과와 후속 작업을 기록했다. |

## 상세 내용

- 지원 Command는 `START_MISSION`, `PAUSE_MISSION`, `RESUME_MISSION`,
  `CANCEL_MISSION`, `EMERGENCY_STOP`, `CLEAR_EMERGENCY_STOP`으로 제한했다.
- 실제 Backend 검증 규칙에 따라 START는 `missionId`·`zoneId`, RESUME은 빈
  parameters, 나머지는 `reason`을 요구하는 Type Union으로 만들었다.
- POST 성공 전 네트워크·503·CSRF 재시도에는 같은 Idempotency Key를 유지한다.
  대상 Robot이나 입력이 바뀌거나 요청이 성공한 뒤에만 새 UUID를 만든다.
- Frontend는 MQTT Client를 만들지 않고 공통 `apiJson`을 통한 REST만 사용한다.
- Route는 `ROBOT_STATUS_VIEW`, 상세 내부 명령 조회는 `ROBOT_COMMAND_VIEW`,
  실행 가능 표시와 향후 버튼은 `ROBOT_COMMAND_EXECUTE`로 책임을 분리했다.
- 로봇 단건 또는 명령 목록의 범위 밖 404는 서버 메시지를 그대로 노출하지 않고
  미존재와 권한 부족을 구분할 수 없는 안내로 표시한다.
- 최초 구현안의 전역 `/commands` 메뉴·Route는 사용자 피드백에 따라 제거하고
  로봇 상세 중심 구조로 정정했다.

## 검증

- 실행한 명령:
  - `npm run lint`
  - `npm test`
  - `npm run build`
  - `git diff --check -- web/frontend/gilbom/src`
  - Command Feature의 MQTT Client·범위 제외 명령 참조와 Route·권한 정적 검색
- 결과:
  - 전체 ESLint 통과
  - Node 단위 테스트 `16 passed`, 실패 `0`
  - 신규 Command API Test `3 passed`
  - TypeScript Project Build와 Vite Production Build 통과
  - 추적 파일 whitespace 오류 없음
  - Command Feature에 MQTT 연결 코드와 `RETURN_TO_CHARGE` 구현 없음
  - 전역 Command Route 없이 `/robots/:robotId` 상세에서 권한별 Command 영역을
    조합하는 구조 확인
- 미실행 항목과 사유:
  - 실제 로그인·Backend·PostgreSQL을 연결한 Browser E2E는 이번
    Type·API Client·권한·Route 작업 범위 이후의 버튼·상태 화면과 함께 수행한다.
  - 실제 TLS Broker·Mock Robot Command 왕복은 별도 `DAY6-B-03` 통합 검증
    범위이므로 실행하지 않았다.

## 영향 및 주의사항

- `/robots/:robotId` 상세 자체에는 `ROBOT_STATUS_VIEW`가 필요하다.
- Command 목록 API는 `ROBOT_COMMAND_VIEW`가 있을 때만 호출한다.
- `ROBOT_COMMAND_EXECUTE`가 있어도 Backend의 Robot `OPERATOR+` 객체 범위와
  업무 상태 검사는 계속 최종 보안 경계다.
- 이번 화면은 데이터 계층·권한·Route 기반까지만 제공한다. 실제 명령 발행
  버튼, 확인 Modal, ACK·Timeout 상세 표시는 아직 구현되지 않았다.
- `PUBLISH_FAILED` 응답도 서버가 요청을 정상 접수한 성공 응답이므로 해당 사용자
  시도는 완료 처리하며, 재발행은 새 사용자 시도로 명시적으로 수행해야 한다.

## 미완료 및 후속 작업

- 상태 기반 시작·일시정지·재개·종료·긴급정지·해제 버튼
- 중요 명령 확인 Modal과 요청 중 중복 클릭 차단
- Command·Result·ACK·실패·거절·Timeout 상세 화면
- `COMMAND_STATUS_CHANGED` 수신 뒤 REST 정본 재조회와 재연결 복구
- 정상·실패·거절·만료·중복 Browser E2E
- 실제 TLS Broker·Mock Robot 전체 왕복 검증
