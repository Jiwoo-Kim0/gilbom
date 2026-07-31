# 변경 로그: 로봇 제어 버튼 및 확인 Modal

- 작성 시각: 2026-07-30 16:02 (Asia/Seoul)
- 작업 유형: 기능
- 상태: 완료

## 작업 요약

로봇 상세 화면에 임무 시작·일시정지·재개·종료·긴급정지·긴급정지 해제 버튼을 추가하고, 로봇 및 임무 상태에 따라 실행 가능 여부를 제어했다. 명령 실행 전 영향과 대상을 확인하는 Modal을 연결했으며 사유 입력, 안전 확인, 중복 제출 방지, Idempotency-Key 재사용 및 접수된 commandId 즉시 표시를 적용했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/frontend/gilbom/src/features/command/commandPolicy.ts` | 추가 | 6개 관리자 명령의 라벨·영향·위험도·입력 조건을 정의하고 연결 상태, 운영 상태, Robot Mode, Mission 상태, 처리 중 명령을 기준으로 버튼 활성화 정책과 Backend 요청 payload 생성을 구현 |
| `web/frontend/gilbom/src/features/command/CommandConfirmModal.tsx` | 추가 | 명령 대상 Robot/Mission/현재 상태와 명령 영향을 표시하는 접근성 Modal을 구현하고 사유 입력, 안전 확인, 위험 명령 강조, 전송 중 닫기 방지 및 commandId 접수 결과 표시를 연결 |
| `web/frontend/gilbom/src/features/command/CommandControlPage.tsx` | 수정 | Robot 상세 객체와 Mission 정본, Command API를 결합해 상태별 버튼을 렌더링하고 권한 확인, 동일 명령 처리 중 차단, 단일 제출 잠금, 실패 재시도용 Idempotency 시도 유지, 생성 결과 즉시 반영을 구현 |
| `web/frontend/gilbom/src/features/command/command.css` | 수정 | 제어 버튼 그리드, 비활성 사유, 위험 명령, 확인 Modal, 사유 입력, 안전 확인, 접수 결과 및 모바일 반응형 스타일을 추가 |
| `web/frontend/gilbom/src/features/robot/RobotDetailPage.tsx` | 수정 | 로봇 상세 데이터 전체를 명령 제어 영역에 전달해 상세 화면 안에서 제어 정책과 확인 Modal이 동작하도록 연결 |
| `web/frontend/gilbom/test/commandPolicy.test.ts` | 추가 | READY/RUNNING/PAUSED/SAFE_STOP 상태별 허용 명령, Offline 및 동일 명령 처리 중 차단, START Mission 코드 payload와 사유 정규화를 검증 |
| `CHANGLOGS/2026-07-30_1602_command-controls-modal.md` | 추가 | 구현 범위, 변경 파일, 검증 결과 및 후속 범위를 기록 |

## 상세 내용

- `READY`에서는 시작·종료, `RUNNING`에서는 일시정지·종료·긴급정지, `PAUSED`에서는 재개·종료·긴급정지, `SAFE_STOP`에서는 긴급정지 해제만 활성화한다.
- Offline 또는 운영 비활성 Robot은 모든 명령을 차단하고, 같은 Command Type이 `CREATED`, `SENT`, `ACCEPTED`이면 중복 실행을 차단한다.
- `START_MISSION`은 Mission 내부 UUID가 아니라 Backend 계약의 `missionCode`와 `patrolAreaCode`를 각각 `missionId`, `zoneId`로 전달한다.
- 일시정지·종료·긴급정지·긴급정지 해제는 사유를 요구하고, 재개·긴급정지 해제는 안전 조건 확인을 요구한다.
- Modal 제출 중에는 닫기, Backdrop/Escape 종료 및 중복 제출을 차단한다. 실패 재시도는 같은 사용자 의도로 보아 동일 Idempotency-Key를 유지하고 성공 또는 Modal 종료 시 초기화한다.
- 브라우저는 MQTT를 직접 사용하지 않고 Command REST API를 통해 Backend에 명령을 요청한다.

## 검증

- 실행한 명령:
  - `npm run lint`
  - `npm test`
  - `npm run build`
  - `git diff --check -- src/features/command src/features/robot/RobotDetailPage.tsx`
- 결과:
  - ESLint 통과
  - Node test 20개 전체 통과
  - TypeScript project build 및 Vite production build 통과
  - 대상 파일 whitespace 오류 없음
- 미실행 항목과 사유:
  - 실제 Backend/MQTT/Robot 연결 E2E는 외부 실행 환경과 Robot 연결이 필요해 이번 로컬 프론트엔드 작업에서는 미실행
  - 브라우저 수동 시각 회귀 검증은 별도 실행 서버 없이 미실행

## 영향 및 주의사항

- 화면의 활성화 정책은 조작 실수를 줄이는 보조 장치이며 Backend의 권한, Robot 범위, Mission 상태 검증이 최종 기준이다.
- 임무 시작에는 `MISSION_VIEW` 권한으로 조회한 READY Mission 정본이 필요하다. 해당 권한이 없거나 조회에 실패하면 시작 버튼만 비활성화되고 다른 상태 기반 명령은 계속 사용할 수 있다.
- 관리자 명령 계약에 포함된 `CLEAR_EMERGENCY_STOP`은 SAFE_STOP 복구를 위해 함께 노출했다.

## 미완료 및 후속 작업

- Command ACK 결과 이력과 실패·Timeout 상세 표시 및 실시간 갱신은 후속 Command 상태 화면 작업에서 구현한다.
- 실제 Backend 연결 환경에서 권한별 노출, 각 Mission 상태 전이, ACK/만료 흐름을 E2E 검증해야 한다.
