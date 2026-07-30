# 변경 로그: Command 상태·ACK·실패·Timeout 화면

- 작성 시각: 2026-07-30 16:56 (Asia/Seoul)
- 작업 유형: 기능
- 상태: 완료

## 작업 요약

로봇 상세의 제어 영역 아래에 최근 Command 목록과 상세 Drawer를 추가했다. ACK를 실행 완료와 구분하고, 실패·거절·Broker 발행 실패·Timeout을 서로 다른 상태와 운영 안내로 표시한다. 기존 로봇 상세 SSE 연결을 재사용해 `COMMAND_STATUS_CHANGED`와 재연결 시 Command REST 정본을 다시 조회하도록 구성했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/frontend/gilbom/src/features/command/CommandHistoryPanel.tsx` | 추가 | 최근 Command 20건 목록, 상태 Badge, 단건 상세 Drawer, 요청·Broker 전송·Robot ACK·최종 Result Timeline, Result Reason·결과 Mode·수신 시각 표시와 실패·Timeout 운영 안내를 구현 |
| `web/frontend/gilbom/src/features/command/commandPresentation.ts` | 추가 | Command Type 한글 라벨, 8개 Command 상태의 진행·ACK·성공·실패·거절·만료 분류, Result 라벨, 실패·Timeout별 재확인 안내와 시각 포맷을 정의 |
| `web/frontend/gilbom/src/features/command/CommandControlPage.tsx` | 수정 | 미완 상태 화면 초안을 최근 목록과 상세 Drawer 구조로 연결하고, 전체 Command마다 Result를 선조회하지 않고 선택한 Command의 단건·Result 정본만 조회하도록 분리했으며 SSE/수동 갱신 시 상세 재조회 Revision을 전달 |
| `web/frontend/gilbom/src/features/command/command.css` | 수정 | 최근 Command 행, 상태 Tone, 실시간 연결 Badge, 우측 상세 Drawer, 상태 안내, Timeline, ACK·Result 상세와 모바일 반응형 스타일을 구현 |
| `web/frontend/gilbom/src/features/robot/RobotDetailPage.tsx` | 수정 | 현재 Route의 Robot SSE 이벤트만 반영하고 `COMMAND_STATUS_CHANGED` 또는 SSE 재연결 시 Command 갱신 Revision과 Stream 상태를 제어 영역에 전달 |
| `web/frontend/gilbom/src/features/robot/useRobotRealtime.ts` | 수정 | Snapshot 갱신 Callback에 파싱된 `RobotRealtimeEvent`를 선택적으로 전달해 소비 화면이 Robot과 이벤트 유형을 구분할 수 있도록 확장 |
| `web/frontend/gilbom/test/commandPresentation.test.ts` | 추가 | ACK와 최종 성공 구분, 실패·거절·Timeout Tone, Timeout·발행 실패 운영 안내와 Command 한글 라벨을 검증 |
| `CHANGLOGS/2026-07-30_1656_command-status-ack-timeout-ui.md` | 추가 | 구현 범위, 변경 파일, 검증 결과와 미실행 E2E 범위를 기록 |

## 상세 내용

- `CREATED`, `SENT`는 진행 상태로 표시하고 `ACCEPTED`는 `Robot 수신 ACK`로 표시해 실행 완료와 구분한다.
- `COMPLETED`만 성공 완료로 표시하며 `FAILED`, `REJECTED`, `PUBLISH_FAILED`, `EXPIRED`는 각각 의미와 안내 문구를 구분한다.
- Timeout은 실행 여부가 불명확할 수 있으므로 즉시 재시도를 유도하지 않고 Robot·Mission 정본을 먼저 확인하도록 안내한다.
- 상세 Drawer는 요청 시각, Broker 전송 시각, Robot ACK 시각, 최종 완료 시각, 만료 시각, Publish 시도 횟수, 오류 코드와 모든 Result의 Robot 수신·완료·서버 수신 시각, Reason Code, 결과 Mode를 표시한다.
- 목록 REST 조회는 최신 20건만 수행하고 Result REST는 선택한 Command에 한해 조회해 초기 요청 수를 줄였다.
- Command 화면에서 별도 EventSource를 만들지 않는다. 로봇 상세가 보유한 기존 SSE 연결의 이벤트를 전달받고, 같은 Robot의 `COMMAND_STATUS_CHANGED` 또는 `CONNECTED` 복구 뒤 목록·선택 상세 REST 정본을 다시 읽는다.

## 검증

- 실행한 명령:
  - `npm run lint`
  - `npm test`
  - `npm run build`
  - `git diff --check -- web/frontend/gilbom/src/features/command web/frontend/gilbom/src/features/robot/RobotDetailPage.tsx web/frontend/gilbom/src/features/robot/useRobotRealtime.ts web/frontend/gilbom/test/commandPresentation.test.ts`
  - Command Feature의 직접 EventSource·MQTT Client·범위 제외 Command 정적 검색
- 결과:
  - ESLint 통과
  - Node 테스트 24개 전체 통과, 실패·Skip 0
  - TypeScript Project Build 및 Vite Production Build 통과
  - 대상 파일 whitespace 오류 없음
  - Command Feature에 직접 EventSource, MQTT 연결 코드, `RETURN_TO_CHARGE` 구현 없음
- 미실행 항목과 사유:
  - 실제 로그인·Backend·PostgreSQL·SSE를 연결한 Browser E2E는 실행 환경 기동이 필요한 후속 `DAY6-B-03` 범위로 미실행
  - 실제 TLS Broker의 정상·실패·거절·Timeout Command 왕복은 Mock Robot 통합 작업에서 검증해야 하므로 미실행
  - 브라우저 수동 시각 회귀 검증은 별도 실행 서버 없이 미실행

## 영향 및 주의사항

- SSE Payload는 변경 알림으로만 사용하며 화면의 최종 상태와 Result는 REST 응답을 정본으로 삼는다.
- `ACCEPTED`는 Robot 수신 ACK일 뿐 실행 성공이 아니다. `COMPLETED` Result가 도착하기 전까지 진행 상태로 유지한다.
- `EXPIRED` 뒤 늦은 Result가 도착해도 Backend 정본이 종결 상태를 되돌리지 않는다는 기존 상태 전이 정책을 따른다.
- 현재 목록은 최신 20건만 표시한다. 전체 과거 이력이 필요하면 Page 이동 또는 더 보기 기능을 별도 작업으로 추가해야 한다.

## 미완료 및 후속 작업

- 실제 TLS Broker·Mock Robot·DB·SSE·React 정상 Command 왕복 E2E
- `FAILED`, `REJECTED`, `PUBLISH_FAILED`, `EXPIRED`, 늦은 Result와 중복 Result Browser E2E
- 권한·Robot Scope·CSRF·감사 Browser 검증
