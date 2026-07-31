# 변경 로그: 로봇 관리 페이지 등록 UI

- 작성 시각: 2026-07-30 16:02 (Asia/Seoul)
- 작업 유형: 기능
- 상태: 완료

## 작업 요약

기존 Backend `POST /api/v1/robots`를 로봇 관리 화면에서 사용할 수 있도록
`RobotManagePage`에 권한 기반 로봇 등록 버튼과 Modal 폼을 추가했다. 로봇 이름과
시리얼 번호를 입력하면 CSRF 보호와 Idempotency-Key를 포함해 등록하고, 성공 후
목록을 갱신하며 발급된 MQTT ID를 안내한다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/frontend/gilbom/src/features/auth/permissions.ts` | 수정 | `ROBOT_REGISTER` 권한 상수를 추가해 등록 버튼 노출 기준을 정의 |
| `web/frontend/gilbom/src/features/robot/robotTypes.ts` | 수정 | 로봇 등록 요청·응답 타입을 추가 |
| `web/frontend/gilbom/src/features/robot/robotApi.ts` | 수정 | `registerRobot()`가 `POST /api/v1/robots`, JSON body, UUID Idempotency-Key와 CSRF replay-safe 옵션을 사용하도록 추가 |
| `web/frontend/gilbom/src/features/robot/RobotManagePage.tsx` | 수정 | 등록 버튼, Modal, 입력 검증, 오류·성공 안내, 등록 후 상태 새로고침 구현 |
| `web/frontend/gilbom/src/app/robot/css/robot.css` | 수정 | 등록 Modal·backdrop·폼·성공 안내 스타일 추가; 기존 상세·제어 스타일은 유지 |
| `CHANGLOGS/2026-07-30_1602_robot-registration-ui.md` | 추가 | 구현 범위와 검증 결과 기록 |

## 상세 내용

- `ROBOT_REGISTER` 권한이 있는 사용자에게만 버튼을 표시한다.
- 등록 요청은 Backend 계약의 `robotName`, `serialNumber`를 사용한다.
- 공백만 입력한 값은 Client에서 먼저 거부한다.
- 성공 시 응답의 MQTT ID를 표시하고 기존 `loadSnapshot()`으로 목록·요약·지도를
  갱신한다.
- 이미지 업로드 API Key 발급은 로봇 등록 이후 별도 Backend 흐름으로 분리했다.
- 현재 작업 트리에 있던 Robot 상세·Fleet·API 기존 변경은 덮어쓰지 않고 그대로
  보존했다.

## 검증

- 실행한 명령:
  - `npm run build`
  - `npx eslint src/features/auth/permissions.ts src/features/robot/RobotManagePage.tsx src/features/robot/robotApi.ts src/features/robot/robotTypes.ts`
  - `git diff --check` 대상 파일 실행
- 결과:
  - TypeScript 컴파일 및 Vite production build 성공
  - 대상 ESLint 성공
  - diff whitespace 오류 없음
- 미실행 항목과 사유:
  - 실제 로그인 세션을 이용한 Browser 등록 E2E는 실행하지 않았다. 실행 Backend와
    권한이 필요한 환경 검증은 후속 통합 단계다.
  - 실제 API 등록은 개발 DB 데이터를 변경하므로 사용자 시연 데이터 요청 없이
    실행하지 않았다.

## 영향 및 주의사항

- 이 변경은 화면·API 호출만 추가하며, Backend 등록 계약과 DB Migration을 변경하지 않는다.
- `ROBOT_REGISTER` 권한이 없는 계정에서는 버튼이 보이지 않는다. 권한이 있어도
  Backend의 인증·CSRF·Idempotency 검증은 그대로 적용된다.
- 현재 다른 미스테이지·미추적 작업이 존재하므로 이번 변경만 별도로 검토·커밋해야 한다.

## 미완료 및 후속 작업

1. 실제 관리자 계정으로 로봇 1~2대를 등록하고 응답의 `robotId`, `mqttId`를 기록한다.
2. 등록된 `robotId`에 이미지 업로드 API Key를 발급해 임베디드에 전달한다.
3. 시연용 ACTIVE Patrol Area·Route와 Mission을 등록한다.
