# 변경 로그: 팀원 B 작업내용 문서 최신화

- 작성 시각: 2026-07-30 23:32 (Asia/Seoul)
- 작업 유형: 문서
- 상태: 완료

## 작업 요약

`DailyLog/`의 Day 5·6 계획과 실행 결과, 관련 `CHANGLOGS/`, 실제 코드·Migration·
커밋을 대조해 `web/docs/팀원B/작업내용`을 최신 상태로 갱신했다.

독립적인 조회 계약인 Robot Telemetry 기간 통계·시계열과, Robot 등록부터 MQTT
Credential 최초 발급·회전·Jetson 전달까지의 Provisioning 흐름을 새 구현 문서로
추가했다. 기존 Patrol Area·시간 기반 Mission 문서는 V32·Mock E2E 완료와
순찰 구역 전용 페이지, Polygon 자동 정렬, CSP 안전 SVG, Mission 선택 미리보기,
상세 지도 플리커링 제거까지 반영했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/docs/팀원B/README.md` | 수정 | 작업내용 폴더 구조에 Telemetry 통계·시계열과 Robot 등록·Credential 관리 문서를 추가 |
| `web/docs/팀원B/작업내용/README.md` | 수정 | 여섯 구현 청크의 최신 완료 상태를 반영하고 새 두 문서의 범위·분리 경계를 목록과 청크 기준에 추가 |
| `web/docs/팀원B/작업내용/로봇-Telemetry-기간통계와-시계열_구현내용.md` | 추가 | 기간 통계·시계열 REST, `sampled_at` 집계, Robot Scope, SSE 5초 재조회, Recharts, 검증·함정·후속 작업을 기록 |
| `web/docs/팀원B/작업내용/로봇-등록과-MQTT-Credential-관리_구현내용.md` | 추가 | Robot 등록, PROVISIONING→ACTIVE, 최초 발급·회전·Registry 복구, 1회 Secret과 Jetson 전달 경계를 기록 |
| `web/docs/팀원B/작업내용/순찰구역과-시간기반-Mission_구현내용.md` | 수정 | Jira `400·404·405·406·525`, V32·Mock E2E 완료, 전용 등록 페이지, Polygon 자동 정렬·SVG, 미수행 Robot 미리보기와 상세 지도 안정화를 반영 |
| `CHANGLOGS/2026-07-30_2332_team-b-work-content-refresh.md` | 추가 | 참조 자료, 변경 파일, 검증 결과와 남은 외부 통합 범위를 기록 |

## 상세 내용

- `DailyLog/2026-07-29_*`에서 Mission·실시간 관제·MQTT·Command의 최초 계획과
  당시 미완료 경계를 확인했다.
- `DailyLog/2026-07-30_*`에서 시간 기반 Mission, Mission 지도, 전체 Mock E2E,
  Command 정상·비정상 E2E의 완료 흐름과 실장비·EC2 후속 범위를 확인했다.
- 최신 변경 로그와 실제 커밋을 우선해 과거 문서의 다음 상태를 정정했다.
  - Mission V32 미커밋·재검증 대기 → V32 커밋·빈 DB·625개 Backend Test 완료
  - Mission 전체 Mock E2E 미완 → 실제 TLS MQTT·Mock·Browser E2E 완료
  - Command Frontend·전체 E2E 미완 → 정상·비정상·보안 Browser E2E 완료
- 보안 설정을 변경하지 않고, 문서에 기존 Permission·Scope·CSRF 경계와
  “설정 무변경” 사실만 기록했다.
- 이미지 Upload·S3·JPEG 검증은 팀원 C 관할로 계속 제외했다.

## 검증

- 실행한 명령:
  - `Get-Content DailyLog/2026-07-29_*`
  - `Get-Content DailyLog/2026-07-30_*`
  - 관련 `CHANGLOGS/2026-07-30_*.md` 전체 내용 확인
  - `rg --files web/backend/src/main/resources/db/migration`
  - `rg`로 Telemetry 상한·Permission·Credential 응답·상태·Endpoint 대조
  - PowerShell 정규식으로 변경 문서의 Markdown 상대 링크 대상 확인
  - `git cat-file -e`로 문서에 기록한 Commit SHA 확인
  - `rg`로 과거 미완료 표현 검색
  - `git diff --check -- <이번 문서 대상 파일>`
- 결과:
  - Image V31·Mission V32 Migration 파일과 관련 커밋을 확인했다.
  - Telemetry 최대 31일·5,000구간, `ROBOT_STATUS_VIEW`, Credential
    `ROBOT_REGISTER`, `mqtt` 응답 구조가 실제 코드와 일치했다.
  - 변경 문서의 모든 상대 링크 대상이 존재했다.
  - 문서에 기록한 `609083f`, `baed2a6`, `85986b5`, `acc4e26`, `79591ce`
    Commit이 모두 존재했다.
  - `Migration V32 IN PROGRESS`, `전체 Mock E2E 미완` 등 오래된 표현이
    작업내용 폴더에서 더 이상 검색되지 않았다.
  - 대상 문서 whitespace 오류가 없었다.
- 미실행 항목과 사유:
  - 이번 변경은 구현 문서 최신화이며 제품 코드·설정·DB를 변경하지 않아
    Backend·Frontend·Browser 테스트를 재실행하지 않았다. 각 기능의 실제
    검증 수치는 해당 원본 변경 로그와 커밋 결과를 인용했다.

## 영향 및 주의사항

- 제품 코드, DB Migration, API 계약, Spring Security, CSRF, Nginx CSP와
  Broker 설정은 변경하지 않았다.
- `DONE (LOCAL)`은 로컬 자동 검증 완료이며 실제 Jetson·EC2·운영 Naver 지도
  검증 완료를 뜻하지 않는다.
- DailyLog 원본은 당시 계획·진행 상태의 역사 기록이므로 덮어쓰지 않았다.
- 기존의 다른 미추적 `CHANGLOGS`, 사용자 PNG와 문서는 이번 변경에 포함하지
  않는다.

## 미완료 및 후속 작업

1. 실제 Jetson에 새 MQTT Credential을 주입하고 TLS CONNECT·Presence·Telemetry를
   확인한다.
2. EC2 Broker·ACL·Nginx SSE와 운영 Naver Origin에서 전체 흐름을 Smoke Test한다.
3. 운영 Telemetry 31일 데이터의 시계열 SQL 실행 계획과 응답시간을 측정한다.
