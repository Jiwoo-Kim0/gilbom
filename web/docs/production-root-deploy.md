# Stage 11 root production deploy entrypoint

Stage 11은 Jenkins `deploy` 계정에 일반 Docker·root 권한을 주지 않고 다음 명령
하나만 허용한다.

```sh
sudo --non-interactive /usr/local/sbin/gilbom-deploy <40자리-full-sha>
```

기존 `infra/scripts/deploy.sh`는 운영자용 범용 스크립트이며 환경변수로 Compose,
환경 파일과 release record 경로를 바꿀 수 있다. 따라서 `deploy` 계정의 sudoers에
직접 허용하지 않는다. `/usr/local/sbin/gilbom-deploy`가 고정 경로를 설정하고
검증한 뒤 필요한 작업을 직접 수행한다.

## 고정 운영 경로

| 용도 | 고정 경로 |
|---|---|
| 승격 Artifact | `/opt/gilbom/artifacts/<release-sha>` |
| Compose | `/opt/gilbom/deploy/compose.prod.yaml` |
| 공개 Runtime 설정 | `/opt/gilbom/deploy/runtime.env` |
| Image 설정 | `/opt/gilbom/deploy/image.env` |
| Secret root | `/opt/gilbom/secrets` |
| Migration 승인 | `/opt/gilbom/migration-approvals/<release-sha>.json` |
| Jenkins 배포 승인 | `/opt/gilbom/deploy-approvals/<release-sha>.json` |
| Jenkins Rollback 승인 | `/opt/gilbom/rollback-approvals/<failed-release-sha>.json` |
| DB·Volume backup | `/opt/gilbom/backups` |
| Release 상태·Manifest | `/var/lib/gilbom/releases` |
| 동시 실행 lock | `/run/gilbom-deploy/deploy.lock` |
| Cosign verifier | `/usr/local/bin/cosign` |
| Release 공개키 | `/etc/gilbom/cosign-release.pub` |

호출자의 `COMPOSE_FILE`, `RUNTIME_ENV_FILE`, `IMAGE_ENV_FILE`, `BACKUP_ROOT`,
`DOCKER_HOST`, `COMPOSE_PROJECT_NAME` 등 override는 모두 거부한다.

## 설치

Stage 10 계정이 준비된 EC2에서 관리자만 실행한다.

먼저 `docs/cosign-blob-signing.md`에 따라 공개키와 고정 Cosign verifier를
설치한다. Private key는 EC2에 전달하지 않는다.

```sh
git -C /opt/gilbom/app-git status --short --branch
git -C /opt/gilbom/app-git rev-parse HEAD

sudo sh \
  /opt/gilbom/app-git/web/infra/scripts/install-production-deploy-entrypoint.sh
```

성공 결과:

```text
Production deploy account verification: PASS
Production deploy entrypoint installation: PASS
```

설치기는 root entrypoint를 `root:root 0755`, migration 승인 도구를
`root:root 0750`으로 설치한다. `deploy` sudoers는 계속
`/usr/local/sbin/gilbom-deploy` 하나만 허용한다.

## Migration 승인

Release metadata에는 모든 `V*.sql`의 version, 파일명, SHA-256이 포함된다.
root entrypoint는 운영 Flyway history와 metadata를 비교해 신규 migration plan을
다음 위치에 먼저 기록한다.

```text
/var/lib/gilbom/releases/<release-sha>/migration-plan.json
```

신규 migration이 없으면 별도 승인이 필요 없다. 신규 migration이 있으면 첫 배포
호출은 backup·Compose 변경 전에 fail-closed되고, 관리자가 plan 원문과 각 SQL
변경을 검토한 뒤 다음 root-only 명령으로 승인한다.

```sh
sudo /usr/local/sbin/gilbom-approve-migrations \
  <40자리-full-sha> \
  <승인자-ID>
```

명령이 출력한 exact plan을 검토하고 같은 full SHA를 다시 입력해야 승인 JSON이
생성된다. 승인 파일은 plan SHA-256을 포함하므로 DB 현재 version이나 migration
목록이 달라지면 재사용할 수 없다. `deploy` 계정은 이 승인 명령을 실행할 권한이
없다.

## 배포와 성공 조건

Jenkins는 Stage 9에서 EC2 immutable Artifact로 승격된 SHA만 전달한다.

```sh
ssh deploy@EC2 \
  'sudo --non-interactive /usr/local/sbin/gilbom-deploy <release-sha>'
```

entrypoint는 다음을 순서대로 수행한다.

1. 단일 full SHA와 고정 경로·root 소유권·권한 검증
2. `flock` 동시 배포 차단
3. Artifact 전체 checksum과 고정 공개키 Cosign Blob 서명 검증
4. 서명 PASS 뒤 `zstd -t`, `zstd -dc | docker load`, 두 image tag와 metadata config ID 검증
5. 운영 Flyway version 조회, 신규 migration plan 비교와 승인 검사
6. 기존 DB·PDF·image·MQTT volume backup 및 checksum 재검사
7. `current.sha`를 `previous.sha`로 기록하고 `image.env` 원자 교체
8. `docker compose up -d --no-build`
9. Backend readiness, Frontend health, Frontend→Backend, MQTT health 검사
10. 적용 후 Flyway version·script 재검사와 release manifest 기록
11. 모든 단계가 성공한 경우에만 `current.sha` 원자 갱신

Image ID 검증은 Docker 저장소 모드에 따라 의미가 달라질 수 있는
`docker image inspect .Id`를 metadata와 직접 비교하지 않는다. 로드된 tag를
`docker image save`로 다시 스트리밍하고 `manifest.json`의 `Config` content
digest를 비교한다. Release Manifest의 `images.*.id`에는 이 portable config
ID를, `images.*.engineId`에는 EC2 Docker가 노출한 로컬 engine ID를 기록한다.

`image.env` 전환 뒤 실패하면 root 배포기는 배포 직전과 실패 직후 Flyway version을
다시 비교한다. 변화가 없을 때만 이전 image를 자동 복원하고 Backend readiness,
Frontend health와 내부 HTTPS smoke 뒤 `rollback-manifest.json`을 기록한다.
Flyway가 변했거나 재조회할 수 없으면 Frontend만 중지하고 신규 Backend·DB,
Artifact, backup과 container log를 보존한
`manual-recovery-required.json`을 기록한다. 이 경우 별도 Jenkins 호환성 승인
없이는 이전 image를 실행하지 않는다. 어느 분기에서도 migration undo나 backup
DB 자동 복원을 수행하지 않는다.

## 운영 증거

성공 시 다음 파일이 남는다.

```text
/var/lib/gilbom/releases/current.sha
/var/lib/gilbom/releases/previous.sha
/var/lib/gilbom/releases/<release-sha>/migration-plan.json
/var/lib/gilbom/releases/<release-sha>/migration-plan.sha256
/var/lib/gilbom/releases/<release-sha>/release-manifest.json
```

Release manifest에는 Artifact manifest hash, Cosign version·공개키·Bundle hash,
두 image ID, Flyway 전·후 version,
migration 승인, Jenkins 승인자 ID·시간, backup bundle, 네 health 결과가
기록된다. Secret 값은 기록하지 않는다. Stage 12 설치 이후에는 exact preflight
hash와 결속된 Jenkins 배포 승인이 없거나 승인 이후 운영 상태가 달라진 경우에도
root 배포기가 fail-closed된다.
