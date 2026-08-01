# 변경 로그: 임무 관리 Smart Commit 패키징

- 작성 시각: 2026-08-02 00:02 (Asia/Seoul)
- 작업 유형: 기능
- 상태: 완료

## 작업 요약

관리자가 임무 화면에서 `CREATED` 임무를 준비 상태로 전환하거나 삭제하고, 임무 상태에 맞춰 시작·일시정지·재개·취소 명령을 직접 발행할 수 있도록 한 백엔드·프론트엔드·DB 변경을 `S15P11D101-672` 단위로 검증하고 패키징했다. 삭제는 서비스 계층과 V35 DB 트리거에서 모두 실행 전 `CREATED` 임무로 제한하며 감사 로그를 남긴다. Mock Robot 구현과 통신 인계 문서는 앞선 `S15P11D101-680` 커밋(`c296f48`)으로 분리했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/backend/src/main/java/com/gilbom/audit/AuditAction.java` | 수정 | 임무 삭제 감사를 위한 `MISSION_DELETED` 액션을 추가했다. |
| `web/backend/src/main/java/com/gilbom/robot/api/MissionController.java` | 수정 | `MISSION_MANAGE` 권한과 CSRF 보호를 적용받는 Mission DELETE API를 추가했다. |
| `web/backend/src/main/java/com/gilbom/robot/mission/MissionManagementService.java` | 수정 | 소유 범위와 실행 이력을 확인하고 untouched `CREATED` 임무만 초기 상태 이력과 함께 삭제한 뒤 감사 기록을 남기도록 했다. |
| `web/backend/src/main/resources/db/migration/V35__allow_created_mission_deletion.sql` | 추가 | 실행 전 `CREATED` 임무만 삭제할 수 있는 DB 트리거, 초기 상태 이력 예외, 애플리케이션 역할 DELETE 권한을 추가했다. |
| `web/backend/src/test/java/com/gilbom/robot/controller/MissionControllerTest.java` | 수정 | Mission DELETE의 204 응답, 권한, CSRF 계약을 검증했다. |
| `web/backend/src/test/java/com/gilbom/robot/integration/MissionManagementIntegrationTest.java` | 수정 | 실제 삭제·감사 생성과 READY 임무 삭제 거부를 통합 검증했다. |
| `web/docs/authorization-matrix.md` | 수정 | 임무 삭제 권한을 `MISSION_MANAGE` 범위에 명시했다. |
| `web/docs/openapi-v1.yaml` | 수정 | Mission DELETE API의 응답·오류 계약을 추가했다. |
| `web/frontend/gilbom/src/features/mission/MissionCommandActions.tsx` | 추가 | 임무 상태·로봇 연결 상태·진행 중 명령을 반영한 시작·일시정지·재개·취소 제어와 확인 Modal을 구현했다. |
| `web/frontend/gilbom/src/features/mission/MissionManagePage.tsx` | 수정 | CREATED 임무의 준비 전환·삭제, 선택 임무 직접 제어, 실시간 갱신 연동을 추가했다. |
| `web/frontend/gilbom/src/features/mission/missionApi.ts` | 수정 | Mission PATCH·DELETE 클라이언트를 추가했다. |
| `web/frontend/gilbom/src/features/mission/missionTypes.ts` | 수정 | Mission 상태 변경 입력 타입을 추가했다. |
| `web/frontend/gilbom/src/features/mission/mission.css` | 수정 | 임무 준비·삭제·명령 제어 패널의 상태별 레이아웃과 반응형 스타일을 추가했다. |
| `web/frontend/gilbom/test/missionApi.test.ts` | 추가 | PATCH·DELETE 경로, 요청 본문, CSRF 전달을 고정했다. |
| `web/frontend/gilbom/e2e/mission-time.spec.ts` | 수정 | 임무 화면의 로봇 지도 요청 fixture를 보강해 시간 기반 Mission 시나리오가 실제 요청 순서에 영향받지 않도록 했다. |
| `CHANGLOGS/2026-08-01_2125_mission-ready-delete.md` | 추가 | 임무 준비 전환·삭제 구현과 기존 검증 결과를 기록했다. |
| `CHANGLOGS/2026-08-01_2221_mission-direct-control-periodic-mock.md` | 추가 | 임무 직접 제어와 Mock 통신 구현 당시의 전체 작업 내용을 기록했다. Mock 파일은 별도 Jira 커밋으로 분리됐다. |
| `CHANGLOGS/2026-08-02_0002_mission-management-smart-commit.md` | 추가 | 최종 커밋 범위, 재검증 결과, 배포 주의사항을 기록했다. |

## 상세 내용

- 임무 삭제는 API·서비스·DB 세 경계에서 실행 이력이 없는 `CREATED` 상태로 제한한다.
- 임무 시작은 서버가 생성한 `missionCode`와 연결된 `patrolAreaCode`를 기존 MQTT 명령 요청 계약에 넣는다.
- 일시정지·재개·취소는 기존 명령 가용성 정책과 중복 명령 차단, Idempotency-Key, 확인 Modal을 그대로 사용한다.
- 커밋에는 임무 관리 런타임 변경만 포함한다. 루트의 기존 문서 삭제, 개인 이미지, 테스트 산출물, 관련 없는 과거 로그는 포함하지 않는다.
- `CHANGLOGS/2026-08-01_2221_mission-direct-control-periodic-mock.md`가 함께 설명하는 Mock Robot 코드는 이미 `S15P11D101-680`으로 커밋되어 이번 커밋에서는 중복 포함하지 않는다.

## 검증

- 실행한 명령:
  - `web/backend/gradlew.bat test --rerun-tasks --tests "com.gilbom.robot.controller.MissionControllerTest" --tests "com.gilbom.robot.integration.MissionManagementIntegrationTest"`
  - `npm test`
  - `npm run lint`
  - `npm run build`
  - `npm run test:e2e:mission`
  - OpenAPI YAML 파싱 및 Mission DELETE 경로 확인
  - Flyway migration 버전 중복 검사
  - 대상 파일 `git diff --check`
- 결과:
  - 백엔드 대상 테스트 23개 통과, 실패 0
  - 프론트 단위 테스트 38개 통과, 실패 0
  - ESLint 통과
  - TypeScript 및 Vite 운영 빌드 통과
  - Mission Playwright E2E 3개 통과
  - OpenAPI YAML 정상, Flyway 중복 없음, diff check 정상
- 미실행 항목과 사유:
  - 전체 백엔드 테스트는 기존 구현 로그에서 774개 통과를 확인했고, 이번 커밋 전에는 직접 영향받는 Controller·Integration 테스트를 `--rerun-tasks`로 재실행했다.
  - 실제 EC2·Jetson MQTT 왕복은 배포와 운영 Credential이 필요한 별도 수용 테스트라 실행하지 않았다.

## 영향 및 주의사항

- V35가 적용되기 전에는 애플리케이션 DB 역할에 삭제 권한이 없어 Mission DELETE가 실패한다.
- 백엔드·프론트엔드·V35를 같은 배포 단위로 반영해야 UI와 API 계약이 일치한다.
- 실행을 시작했거나 상태 이력이 추가된 임무는 물리 삭제하지 않고 기존 상태 전이·취소 정책으로 보존한다.
- Vite 빌드의 500 kB 초과 chunk 경고는 기존 번들 최적화 경고이며 빌드는 성공했다.

## 미완료 및 후속 작업

- EC2 배포 후 Flyway V35 적용 여부와 Mission DELETE 204 응답을 확인한다.
- 실제 로봇 연결 상태에서 START·PAUSE·RESUME·CANCEL 명령과 ACK가 임무 화면에 반영되는지 수용 테스트한다.
- Jira Smart Commit의 상태 전이는 원격 저장소에 push된 뒤 확인한다.
