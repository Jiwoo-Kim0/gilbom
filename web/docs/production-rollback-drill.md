# Stage 14 isolated Rollback drill

Stage 14는 운영 EC2가 아니라 Jenkins의 DinD TLS Docker Builder에서 실행한다.
운영 SSH Credential, 운영 Artifact 경로, 운영 도메인과 Docker Socket을 사용하지
않는다. 모든 컨테이너·network·Volume은
`gilbom-rollback-drill-<build>-<scenario>` Compose Project에 한정된다.

Stage 13의 기존 통합 테스트는 root 배포기의 `image.env`, Manifest, 승인 파일과
분기 로직을 직접 검증한다. Stage 14는 같은 A/B/C 결정을 실제 Docker image,
PostgreSQL Flyway 상태, healthcheck와 named Volume으로 재현한다. 두 검증을 함께
통과해야 Rollback 계약과 컨테이너 런타임 동작이 모두 입증된다.

## 실제 검증 범위

### A. Migration 없는 실패

V35 Previous 스택에 DB row, 로봇 이미지, PDF fixture를 기록한다. 같은 V35
Candidate의 Backend health를 실패시키고 Previous image tag와 config를 로컬 Docker
저장소에서 삭제한다. Compose 교체가 남긴 정지 컨테이너는 훈련 Project label과
정지 상태를 모두 확인한 경우에만 먼저 제거하며, 실행 중이거나 다른 Project인
컨테이너는 거부한다. 이전 Artifact의 checksum과 zstd stream을 검증한 뒤
`docker load`하여 Previous Backend·Frontend를 복구한다.

성공 조건:

- Flyway `V35 → V35`
- `AUTOMATIC_ROLLBACK_SUCCEEDED`
- Previous Artifact reload PASS
- Backend health와 Frontend→Backend smoke PASS
- PostgreSQL·robot image·report PDF Volume 이름과 내용 유지

### B. 호환 가능한 Migration

Candidate가 additive V36을 적용한 뒤 health 실패를 발생시킨다. Previous image를
삭제한 상태에서 승인 파일 없이 Rollback을 시도하고, 이미지가 다시 load되지 않은 채
차단되는지 확인한다. 이후 exact preflight SHA-256에 결속된 훈련용 호환 승인을
생성하고 Previous Artifact를 load한다. Previous Backend는 V36을 유지한 DB에서
정상 기동해야 한다.

성공 조건:

- Flyway `V35 → V36`, Rollback 후에도 V36
- 승인 전 `ROLLBACK_BLOCKED_EXPLICIT_COMPATIBILITY_APPROVAL_REQUIRED`
- 승인 후 `MANUALLY_APPROVED_ROLLBACK_SUCCEEDED`
- DB Migration undo와 backup restore 미실행
- 세 영속 Volume 유지

### C. 비호환 Migration

Candidate V37은 Previous Backend가 요구하는 DB column을 제거한다. Previous
Backend의 compatibility probe가 현재 V37 DB에 대해 실패해야 한다. 자동 Rollback은
실행하지 않고 Frontend만 중지한다.

성공 조건:

- `INCOMPATIBLE_PREVIOUS_BACKEND`
- `MANUAL_RECOVERY_REQUIRED`
- Frontend STOPPED
- Candidate Backend와 DB PRESERVED
- DB·이미지·PDF Volume 유지
- Migration undo와 backup restore 미실행

## Jenkins Job

`gilbom-rollback-drill` Pipeline Job을 생성한다.

| 항목 | 값 |
|---|---|
| Definition | `Pipeline script from SCM` |
| SCM | `Git` |
| Repository URL | 프로젝트 GitLab URL |
| Credentials | `gitlab-scm-read` |
| Branch Specifier | `*/master` |
| Script Path | `web/Jenkinsfile.rollback-drill` |
| Lightweight checkout | 해제 |
| Build Trigger | 모두 해제 |

이 Job에는 입력 parameter나 운영 승인 단계가 없다. 운영 배포가 아니라 격리된
훈련이기 때문이다. `Build Now`로 실행한다.

Job은 실행 전에 다음을 fail-closed로 확인한다.

- `DOCKER_HOST=tcp://docker-builder:2376`
- `DOCKER_TLS_VERIFY=1`
- `/var/run/docker.sock` 미존재
- 운영 SSH Credential 환경변수 미주입
- 운영 host·domain 호출 코드 미존재
- Frontend는 고정 비root UID 101로 실행하며 모든 capability를 제거

## 결과 확인

성공 로그:

```text
Rollback drill scenario A: PASS
Rollback drill scenario B: PASS
Rollback drill scenario C: PASS
Production rollback drill evidence verification: PASS
Production rollback drill: PASS
Finished: SUCCESS
```

Jenkins는 `.artifacts/rollback-drill/**`을 fingerprint와 함께 보관한다. 핵심 파일은
`summary.json`이며 세 시나리오 결과, DinD 격리, Artifact reload와 세 Volume 보존
결과를 포함한다. `SHA256SUMS`로 모든 drill evidence의 무결성을 확인한다.
시나리오가 중간에 실패해도 정리 전에 해당 Project의 `failure-compose.log`,
`failure-compose-ps.txt`, runner 종료 코드를 먼저 보관한다.

A와 B는 시나리오 증거를 기록한 뒤 다음 시나리오와 image tag 참조가 겹치지 않도록
컨테이너와 network만 먼저 정리한다. 이때 세 named Volume이 모두 남아 있는지 다시
확인한다. 전체 증거 생성과 검증이 끝나면 모든 훈련 Project를 `down --volumes`로
정리한다. 고정된 project prefix 검증 때문에 운영 Compose Project는 정리 대상이
될 수 없다.
