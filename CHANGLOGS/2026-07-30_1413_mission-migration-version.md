# 변경 로그: Mission 마이그레이션 버전 충돌 해소

- 작성 시각: 2026-07-30 14:13 (Asia/Seoul)
- 작업 유형: 수정
- 상태: 완료

## 작업 요약

현재 `origin/master`에 이미 존재하는 V29 CSRF 방어 및 V30 Report 도메인
마이그레이션을 정본으로 유지하고, 팀원 B의 시간 기반 Mission 마이그레이션을
V29에서 V31로 이동했다. 관련 문서의 버전 표기와 전체 적용 범위를 V31 기준으로
갱신했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/backend/src/main/resources/db/migration/V29__add_time_based_mission_contract.sql` | 삭제 | 마스터의 V29 CSRF 마이그레이션과 버전이 겹치지 않도록 시간 기반 Mission DDL의 기존 경로를 제거했다. 내용은 V31 경로로 그대로 이동했다. |
| `web/backend/src/main/resources/db/migration/V31__add_time_based_mission_contract.sql` | 추가 | `planned_duration_sec` 제약·Backfill과 기존 ACTIVE Area의 내부 `AUTONOMOUS_DEFAULT` Route 보강 DDL을 V31로 이동했다. |
| `web/docs/database-migrations.md` | 수정 | V29 CSRF, V30 Report, V31 Mission 순서를 기록하고 정상 Flyway 적용 범위를 V1~V31로 갱신했다. |
| `web/docs/time-based-mission-contract.md` | 수정 | 기존 ACTIVE Area 보강 및 Testcontainers 검증 대상 마이그레이션 버전을 V31로 정정했다. |
| `web/docs/2026-07-28-story-subtask-status.md` | 수정 | Mission 정본 범위를 `V22~V25·V31`로 정정해 V26~V30의 타 모듈 마이그레이션과 구분했다. |
| `CHANGLOGS/2026-07-30_1413_mission-migration-version.md` | 추가 | 버전 이동 근거, 검증 결과와 기존 로컬 DB 주의사항을 기록했다. |

## 상세 내용

- 로컬 `origin/master`의 마이그레이션 목록을 대조해
  `V29__limit_csrf_token_session_creation.sql`과
  `V30__harden_report_domain_invariants.sql`이 마스터 정본임을 확인했다.
- 두 마스터 파일의 이름과 내용은 변경하지 않았다.
- 시간 기반 Mission 마이그레이션만
  `V29__add_time_based_mission_contract.sql`에서
  `V31__add_time_based_mission_contract.sql`로 이동했다.
- 과거 변경 로그는 당시 사실을 보존해야 하므로 기존 V29 경로 표기를 수정하지
  않았다.

## 검증

- 실행한 명령:
  - PowerShell로 `web/backend/src/main/resources/db/migration/V*.sql`의 버전별 중복 검사
  - `git ls-tree --name-only origin/master:web/backend/src/main/resources/db/migration`
  - `git merge-base --is-ancestor origin/master HEAD`
  - `.\gradlew.bat test --rerun-tasks --tests "com.gilbom.robot.integration.PatrolMissionSchemaIntegrationTest" --tests "com.gilbom.report.integration.ReportDomainServiceIntegrationTest" --tests "com.gilbom.auth.ratelimit.CsrfTokenIssuanceRateLimitFilterTest"`
  - `git diff --check`
  - 현재 Docker DB의 `flyway_history.flyway_schema_history` V29~V31 조회
- 결과:
  - Flyway 버전 중복 없음
  - 마스터에는 V29 CSRF와 V30 Report가 존재하고 V31은 비어 있음을 확인
  - 현재 브랜치 HEAD가 확인한 `origin/master`를 이미 포함함
  - 빈 PostGIS Testcontainers DB에서 V1~V31 전체 적용을 포함한 관련 테스트 27건 통과, 실패 0, 건너뜀 0
  - 변경 파일 whitespace 오류 없음
  - 현재 Docker DB에는 과거 Mission V29가 checksum `290922415`로 이미 적용돼 있음을 확인
- 미실행 항목과 사유:
  - Backend 전체 테스트는 실행하지 않았다. 이번 변경은 SQL 내용 변경 없이 버전과 문서 참조만 이동했으며, Flyway 전체 적용을 수행하는 Mission Schema와 Report·CSRF 관련 테스트 27건으로 범위를 검증했다.
  - 현재 Docker DB에는 이동 전 Mission V29 이력이 있어 정본 V29와 checksum이 다르므로, 해당 영속 DB에 새 마이그레이션을 직접 적용하지 않았다.

## 영향 및 주의사항

- 마스터의 올바른 V29·V30까지 적용한 DB 또는 빈 DB는 V31 Mission을 후속 적용할
  수 있다.
- 이동 전 `V29__add_time_based_mission_contract.sql`을 한 번이라도 적용한 DB는
  파일명 변경만으로 복구되지 않는다. Flyway가 V29 checksum·description 불일치로
  시작을 막으므로 로컬 DB 재생성 또는 운영 데이터 보존을 전제로 한 별도
  schema-history 정정 절차가 필요하다.
- 향후 마스터에 V31 이상이 추가되면 병합 전에 다시 버전 선점을 확인해야 한다.

## 미완료 및 후속 작업

- 현재 Docker 로컬 DB는 이동 전 Mission V29 이력을 가진다. 데이터 보존이
  불필요하면 DB volume을 재생성하고, 보존이 필요하면 자동 `repair`를 실행하지
  말고 V29 CSRF DDL 적용 여부와 Mission DDL 원복 여부를 포함한 교정 절차를 별도로
  수립한다.
- 마이그레이션 이동과 직접 참조 문서 및 이 로그는 로컬 커밋으로 묶는다.
  원격 푸시는 수행하지 않는다. 기존 변경이 함께 존재하는
  `web/docs/2026-07-28-story-subtask-status.md`는 다른 작업 내용을 임의로
  커밋하지 않기 위해 이번 커밋에서 제외한다.
