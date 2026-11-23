# 변경 로그: Flyway V41 CI 계약 동기화

- 작성 시각: 2026-08-03 18:12 (Asia/Seoul)
- 작업 유형: 수정
- 상태: 완료

## 작업 요약

이미지 API Key Runtime INSERT 권한을 위한 Flyway V41이 추가됐지만 Jenkins,
운영 DB 검증 SQL, Release metadata, Mattermost 진단과 현행 문서는 V40에
머물러 있었다. Compose 오류 해소 후 다음 gate에서 발생할 실패를 방지하도록
CI·DB·Release 계약을 V1~V41, 41개 Migration, latest V41로 동기화했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/Jenkinsfile` | 수정 | Flyway gate 단계명·이력 개수·최대 Version·CI 결과 ID를 V41로 갱신 |
| `web/infra/postgres/verify-empty-database.sql` | 수정 | 빈 DB에 V1~V41 41개가 성공 적용됐는지 검증하도록 범위와 개수 갱신 |
| `web/infra/postgres/verify-production-database.sql` | 수정 | 운영 DB 읽기 전용 Flyway 감사 기준을 V1~V41 41개로 갱신 |
| `web/scripts/generate-build-metadata.py` | 수정 | 필수 CI 결과 ID, Migration 연속 범위, `latestVersion`을 V41로 갱신 |
| `web/scripts/generate-cicd-notification.py` | 수정 | Mattermost CI 진단의 Flyway gate ID와 표시명을 V41로 갱신 |
| `web/scripts/test-cicd-notification.py` | 수정 | V41 통과 단계를 기대하고 현재 Python interpreter를 사용해 Windows에서도 자식 프로세스 테스트가 실행되도록 개선 |
| `web/scripts/verify-ci-release-contract.sh` | 수정 | Jenkins Flyway V41 단계, 41개 이력, 최대 V41 정적 계약 검증으로 갱신 |
| `web/scripts/verify-day8-infrastructure.sh` | 수정 | 빈 DB 인프라 계약의 Flyway 개수 기대치를 41로 갱신 |
| `web/scripts/verify-production-runtime.sh` | 수정 | 운영 Runtime 빈 Volume 검증 설명을 V1~V41로 갱신 |
| `web/scripts/verify-release-artifacts.sh` | 수정 | Release metadata의 latest V41과 Migration 41개를 강제 |
| `web/scripts/verify-stage15-cicd-operations-contract.sh` | 수정 | Mattermost CI 진단 계약이 V41 gate를 사용하는지 검증 |
| `web/docs/day8-completion-runbook.md` | 수정 | Day 8 완료 검증의 Flyway 범위를 V1~V41로 갱신 |
| `web/docs/jenkins-pipeline.md` | 수정 | Jenkins 빈 DB gate와 Release metadata Migration 범위를 V1~V41로 갱신 |
| `CHANGLOGS/2026-08-03_1812_flyway-v41-ci-contract.md` | 추가 | V41 CI·DB·Release 계약 동기화와 검증 결과를 기록 |

## 상세 내용

- Pipeline 실패 로그에 표시된 후속 단계명이 `Flyway V1-V40`인 것을
  확인하고 실제 Migration 파일 41개와 대조했다.
- Jenkins Flyway query는 성공 이력 41개, 최소 V1, 최대 V41을 함께 검증한다.
- Release metadata 생성·검증과 CI 결과 파일 ID를 모두 `flyway-v1-v41`로
  맞춰 Pipeline 성공 후 Artifact 생성 단계에서의 추가 실패를 방지했다.
- 빈 DB와 운영 DB 읽기 전용 검증 SQL은 V41을 누락한 DB를 정상으로
  판정하지 않도록 범위와 개수를 함께 갱신했다.

## 검증

- 실행한 명령:
  - V40 구 계약 참조와 V41 신 계약 참조 `rg` 대조
  - `generate-build-metadata.py`의 `migration_evidence()` 직접 실행
  - `python web/scripts/test-cicd-notification.py`
  - Linux Alpine 기반 Container의 변경 Shell script `sh -n`
  - Linux Alpine 기반 Container의 `sh scripts/verify-ci-release-contract.sh`
  - 대상 파일 `git diff --check`
- 결과:
  - 현행 `web/` 계약에 사용 중인 V40 gate 참조 0건.
  - Migration evidence: 41개, latest Version V41, V1~V41 연속성 통과.
  - CI/CD 알림 Test 3건 통과, 실패 0건.
  - 변경 Shell script 문법 검사 통과.
  - `CI stages 4-8 release contract: PASS`.
  - `git diff --check` 통과.
- 미실행 항목과 사유:
  - Jenkins 전체 Pipeline은 원격 Job 상태를 변경하므로 실행하지 않았다.
  - `verify-day8-infrastructure.sh`의 전체 실행은 Linux Docker CLI·Jenkins Runtime
    환경을 함께 필요로 해 로컬 Windows에서 실행하지 않았고, 변경 Shell
    문법과 해당 정적 계약은 별도 검증했다.

## 영향 및 주의사항

- V41을 적용하지 않은 DB는 Jenkins 빈 DB gate와 운영 DB 읽기 전용 감사에서
  의도대로 실패한다.
- CI 결과 ID가 `flyway-v1-v41`로 바뀌었으므로 Release metadata와 Mattermost
  진단은 새 Pipeline 실행에서 생성된 결과 파일을 사용해야 한다.
- 기존 Release Artifact는 이미 검증된 SHA의 불변 산출물이므로 변경하지
  않는다.

## 미완료 및 후속 작업

- 원격 반영 후 Jenkins CI에서 `Production Compose contract`, `Flyway V1-V41`,
  Release metadata·Mattermost 알림 gate까지 통과하는지 확인한다.
