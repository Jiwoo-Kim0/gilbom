# 변경 로그: Flyway·Release Artifact 병합 충돌 해결

- 작성 시각: 2026-08-02 19:55 (Asia/Seoul)
- 작업 유형: 수정
- 상태: 완료

## 작업 요약

팀 공통 브랜치의 `V36__narrow_defect_types.sql`과 민호 브랜치의 V36·V37 Migration이 병합되면서 발생한 Flyway 버전 중복 및 `verify-release-artifacts.sh` 충돌을 해결했다. 먼저 작성된 팀 공통 V36을 유지하고 민호 브랜치의 `zone_id` 제약 해제와 Mission 종료 이력 Migration을 각각 V37·V38로 이동했다. Release metadata, Jenkins gate, DB 검증 SQL과 문서의 최신 버전도 V38로 동기화했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/backend/src/main/resources/db/migration/V36__relax_zone_id_format.sql` | 삭제 | 팀 공통 V36과의 중복을 제거하기 위해 동일 내용을 V37 파일로 이동 |
| `web/backend/src/main/resources/db/migration/V37__relax_zone_id_format.sql` | 추가 | 기존 `zone_id` 형식 제약 해제 Migration을 V37로 재번호화 |
| `web/backend/src/main/resources/db/migration/V37__soft_delete_terminal_missions.sql` | 삭제 | Mission 종료 이력 Migration을 V38로 이동하기 위해 기존 V37 경로 제거 |
| `web/backend/src/main/resources/db/migration/V38__soft_delete_terminal_missions.sql` | 추가 | 종료 Mission 논리 삭제와 `COMPLETE_MISSION` 제약 Migration을 V38로 재번호화 |
| `web/scripts/verify-release-artifacts.sh` | 수정 | 충돌 표식을 제거하고 최신 Flyway V38, Migration 38개, 파일명·SHA-256 형식 검증을 함께 유지 |
| `web/scripts/generate-build-metadata.py` | 수정 | CI 결과 ID와 연속 Migration 검증 범위를 V1~V38로 변경하고 최신 버전을 V38로 기록 |
| `web/Jenkinsfile` | 수정 | Flyway stage, DB 이력 개수·최댓값, CI 결과 ID를 V38 기준으로 변경 |
| `web/scripts/verify-ci-release-contract.sh` | 수정 | Jenkins Flyway stage 계약을 `Flyway V1-V38`로 변경 |
| `web/infra/postgres/verify-empty-database.sql` | 수정 | 빈 DB의 성공 Migration 38개와 V1~V38 연속 범위를 검증 |
| `web/infra/postgres/verify-production-database.sql` | 수정 | 운영 DB의 성공 Migration 38개와 V1~V38 연속 범위를 검증 |
| `web/scripts/verify-day8-infrastructure.sh` | 수정 | 빈 DB 계약의 Migration 개수 기대값을 38로 변경 |
| `web/scripts/verify-production-runtime.sh` | 수정 | 운영 Runtime 검증 설명을 Flyway V1~V38로 갱신 |
| `web/docs/database-migrations.md` | 수정 | V36 결함 유형 축소, V37 zone_id 제약 해제, V38 Mission 종료 이력 순서와 파일 경로를 명시 |
| `web/docs/jenkins-pipeline.md` | 수정 | Release pipeline과 build metadata의 Migration 범위를 V1~V38로 갱신 |
| `web/docs/day8-completion-runbook.md` | 수정 | EC2 자동 gate의 Flyway 확인 범위를 V1~V38로 갱신 |
| `web/docs/jiw/작업내용/결함-목록-지도-상세화면_작업내용.md` | 수정 | `zone_id` 형식 제약 해제 기준을 V36에서 V37로 정정 |
| `web/scripts/verify-release-artifacts_{BASE,LOCAL,REMOTE,BACKUP}_502.sh` | 삭제 | SourceTree가 만든 비교용 미추적 충돌 복사본 제거. 양쪽 원본은 Git 부모 커밋에서 복구 가능 |
| `CHANGLOGS/2026-08-02_1955_flyway-artifact-merge-resolution.md` | 추가 | 충돌 원인, 버전 재배치, 검증 결과와 로컬 DB 주의사항 기록 |

## 상세 내용

- 팀 공통 V36은 2026-08-02 17:54 커밋, 민호 브랜치 V36·V37은 19:39 커밋으로 확인되어 먼저 확정된 팀 공통 번호를 유지했다.
- 최종 Migration 순서는 `V36 결함 유형 축소 → V37 zone_id 형식 제약 해제 → V38 Mission 종료 이력`이다.
- Artifact 검증은 민호 쪽 최신 버전 수정과 상대 브랜치의 Migration manifest 강화 검증을 모두 보존했다.
- 로컬 실행 DB는 변경 전에 `V36__relax_zone_id_format.sql`, `V37__soft_delete_terminal_missions.sql`이 적용된 상태임을 읽기 전용으로 확인했다.

## 검증

- 실행한 명령:
  - Migration 파일 개수·중복 버전 검사
  - `python -B`로 `generate-build-metadata.py`의 `migration_evidence()` 실행
  - Git for Windows `sh.exe -n`으로 변경 Shell script 문법 검사
  - `sh scripts/verify-ci-release-contract.sh`
  - `sh scripts/verify-day8-infrastructure.sh`
  - `./gradlew.bat test --no-daemon --rerun-tasks --tests com.gilbom.robot.integration.PatrolMissionSchemaIntegrationTest`
  - `git diff --check`
- 결과:
  - Flyway Migration 38개, 중복 버전 없음, 최신 버전 V38
  - Shell 문법 검사 통과
  - CI Release 계약 PASS
  - Day 8 인프라 정의 PASS
  - Backend 통합 테스트 11건 통과, 실패·Skip 0건
  - diff 공백 오류 없음
- 미실행 항목과 사유:
  - 현재 로컬 Docker DB 재생성은 기존 데이터를 삭제하는 파괴적 작업이므로 자동 실행하지 않음
  - 실제 Release Artifact 전체 생성은 Docker image build, SBOM/CVE 결과와 전체 CI gate가 필요한 통합 Pipeline 범위이므로 미실행

## 영향 및 주의사항

- 새 빈 DB와 아직 V36을 적용하지 않은 환경은 V1~V38을 순서대로 적용할 수 있다.
- 현재 로컬 DB처럼 민호 브랜치의 옛 V36·V37을 이미 적용한 환경은 소스 파일 재번호화 후 Flyway validate가 실패할 수 있다. 해당 DB는 데이터를 백업한 뒤 개발 volume을 재생성하거나, 운영 절차에 따른 별도 이력 조정 계획이 필요하다.
- 이미 공유·운영 환경에 적용된 Migration 이력을 임의로 UPDATE하거나 `repair`하지 않는다.

## 미완료 및 후속 작업

- 병합 커밋은 사용자가 전체 병합 변경을 확인한 뒤 생성한다.
- 기존 로컬 DB 데이터를 보존해야 한다면 재생성 전에 백업·복원 범위를 별도로 결정한다.
