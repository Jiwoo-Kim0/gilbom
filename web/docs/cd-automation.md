# 단일 EC2 운영 자동 CD 기준선

이 문서는 별도 staging EC2를 만들 수 없는 현재 인프라 제약에서 `master` CI
성공 뒤 운영 EC2에 자동 배포하는 경계를 정의한다. 운영 서버를 staging으로
위장하거나 운영 전용 검사를 생략하지 않는다. CI의 Docker 작업은 Jenkins TLS
DinD에서 격리하고, 배포는 기존 root 소유 진입점을 그대로 재사용한다.

## 확정된 흐름

```text
master SCM Polling
  → 전체 CI/Test/Security
  → SBOM/CVE 정책
  → images.tar.zst + compose.prod.yaml 릴리스 생성
  → image와 전체 SHA256SUMS Cosign 서명·부정 테스트
  → 운영 Artifact host immutable promotion
  → Jenkins console Secret scan
  → gilbom-production-auto-deploy 비동기 예약
  → production preflight
  → root deploy Lock·backup·docker compose up --no-build
  → production HTTPS/Health/Manifest 검증
  → Mattermost
```

현재 Jenkins executor가 한 개이므로 CI는 `wait: false`로 운영 자동 Job을
예약하고 종료한다. 하위 Job을 기다리게 바꾸면 단일 executor에서 교착될 수 있다.
CI와 운영 자동 Job의 SUCCESS/FAILURE 및 Mattermost 증거는 서로 독립적으로
보관한다.

## 활성화와 호출 권한

Jenkins 전역 환경변수는 다음 값으로 시작한다.

```text
GILBOM_PRODUCTION_AUTO_CD_ENABLED=true
```

긴급 중단이 필요하면 값을 `false`로 바꾼다. CI는 값이 정확히 `true`일 때만
`gilbom-production-auto-deploy`를 예약하고, 운영 Job도 같은 값을 다시 확인한다.

운영 자동 Job에는 독립 SCM trigger나 사람의 Build 동작이 없다. 다음 조건을 모두
만족해야 실행된다.

- upstream Job이 정확히 `gilbom-ci-baseline`
- CI가 전달한 값이 40자리 full SHA
- SHA가 `origin/master` 이력에 존재
- 운영 Artifact가 이미 immutable promotion 완료
- root preflight의 Cosign 상태 `PASS`
- 전체 SHA-256 checksum `PASS`
- Critical `0`, 미승인 High `0`
- Flyway compatibility `COMPATIBLE_NO_SCHEMA_CHANGE`
- pending migration `0`

Jenkins Upstream Cause의 project 문자열은 설치 환경에 따라 표시용 선행·후행
공백이 붙을 수 있다. Cause 유형은 반드시
`hudson.model.Cause$UpstreamCause`로 제한하고 project 값의 양끝 공백만 제거한
후 `gilbom-ci-baseline`과 정확히 비교한다. Jenkins 큐가 동일 upstream cause를
복수로 기록해도 일치하는 원인이 하나 이상이면 허용한다. 수동 Build처럼 일치하는
typed upstream cause가 없으면 계속 거부한다.

Job의 일반 사용자 `Build` 권한을 제거한다. 수동으로 실행하면 upstream cause
검증에서 fail-closed된다.

Job을 처음 만든 직후에는 Declarative `FULL_COMMIT_SHA` parameter가 아직 Jenkins
Job 설정에 반영되기 전이라 최초 upstream build가 parameter bootstrap 용도로 한
번 실패할 수 있다. 첫 실행이 parameter를 등록하므로 코드를 바꾸거나 수동 운영
배포를 실행하지 말고 `gilbom-ci-baseline`을 같은 master 상태로 한 번 다시
실행한다. 이후 호출부터 검증된 `FULL_COMMIT_SHA`가 명시적으로 환경에 고정된다.

## Migration 경계

신규 Flyway migration은 자동 운영 배포 대상이 아니다. preflight가 migration을
한 개라도 발견하면 image 전환과 Compose 변경 전에 자동 Job을 실패시킨다. 해당
릴리스는 기존 `gilbom-deploy` 수동 승인 Job으로 배포하고, DB version이 반영된
다음 무마이그레이션 릴리스부터 자동 CD가 다시 진행된다.

현재 운영에 V41이 이미 반영됐다면 Release Manifest의
`flyway.afterVersion == 41`을 확인한다. 아직 V40이면 V41 릴리스는 기존 수동
승인 Job으로 한 번 반영해야 한다.

```sh
release_sha="$(sudo cat /var/lib/gilbom/releases/current.sha)"
sudo jq -e --arg sha "$release_sha" \
  '.releaseSha == $sha and .flyway.afterVersion == 41' \
  "/var/lib/gilbom/releases/$release_sha/release-manifest.json" \
  >/dev/null \
  && echo "Production Flyway V41: PASS"
```

## 재사용하는 root 보안 경계

자동 Job은 기존 root 배포기를 호출하므로 다음 검증은 Pipeline 문자열이 아니라
EC2 root 경계에서 다시 수행된다.

- Artifact 디렉터리 root 소유·불변 mode·정확히 열두 개 파일
- 전체 `SHA256SUMS` 서명과 `images.tar.zst.sha256`
- 고정 Cosign `v3.1.2`, 고정 공개키, image/manifest Blob Bundle
- 서명된 `compose.prod.yaml`과 서버 전용 `runtime.env`의 완전한 렌더링
- image name·ID와 full SHA
- Critical 및 미승인 High 정책
- exact preflight·현재 SHA·migration plan에 결속된 자동 승인
- `/run/gilbom-deploy/deploy.lock`의 non-blocking `flock`
- 배포 직전 암호화 DB·Volume backup
- `docker compose up --detach --no-build`
- Backend·Frontend·MQTT·내부 HTTPS health
- Release Manifest, `current.sha`, `previous.sha`

Cosign, checksum 또는 후보 Compose 렌더링이 실패하면 `docker load`와 서비스
변경 전에 차단된다. `runtime.env`와 Docker Secret은 Artifact에 포함하지 않으며
EC2 root 소유 파일로 유지한다. 저장소 Compose에 `${NAME:?…}` 필수 변수가
추가됐는데 운영 `runtime.env`에 값이 없으면 preflight가 자동 배포를 차단한다.

Health 실패 시 Flyway version 변화가 없을 때만 이전 서명 Artifact로 자동
Rollback한다. version 변화 또는 확인 불가 상태면 Frontend를 차단하고 backup과
장애 증거를 보존한 `MANUAL_RECOVERY_REQUIRED` 상태로 전환한다. 운영 자동 Job은
migration `0`만 허용하므로 정상 자동 CD 경로의 Health 실패는 이전 release 자동
Rollback 대상이다. 이때 이전 image뿐 아니라 배포 직전
`previous-compose.prod.yaml` snapshot도 함께 복원한다.

## 새 Artifact 형식 최초 반영

새 CI를 실행하기 전에 EC2의 root 승격 helper와 배포 entrypoint를 먼저 갱신한다.
구 helper는 열두 파일 형식을 알지 못하므로 이 순서를 바꾸면 promotion이
fail-closed된다.

```sh
cd /opt/gilbom/app-git/web
git pull --ff-only

sudo install -o root -g root -m 0755 \
  infra/scripts/promote-ci-release-artifacts.sh \
  /usr/local/sbin/gilbom-promote-ci-release-artifacts

sudo sh infra/scripts/install-production-deploy-entrypoint.sh
sudo sh infra/scripts/install-production-deploy-job-entrypoints.sh
```

설치 완료 뒤 master를 push해 CI → promotion → 자동 운영 배포를 실행한다.

## Jenkins Job

다음 Job 하나를 `Pipeline script from SCM`으로 만든다.

| Job 이름 | Script Path | 독립 Trigger |
|---|---|---|
| `gilbom-production-auto-deploy` | `web/Jenkinsfile.production-auto` | 없음 |

SCM, `gitlab-scm-read`, `*/master`는 기존 Job과 동일하게 설정하고 shallow clone은
사용하지 않는다. 이 Job은 기존 운영 Credential을 그대로 사용한다.

| Credential ID | 용도 |
|---|---|
| `gilbom-artifact-upload-ssh` | preflight·승인 기록·Manifest 회수 |
| `gilbom-artifact-known-hosts` | 운영 SSH host key 고정 |
| `gilbom-production-deploy` | forced-command `deploy` 계정 |
| `gilbom-mattermost-webhook` | 운영 자동 배포 결과 알림 |

별도 staging Credential, DNS, Instance ID 환경변수는 사용하지 않는다.

## 운영 확인

자동 배포 성공 뒤 다음을 확인한다.

```sh
sudo cat /var/lib/gilbom/releases/current.sha
release_sha="$(sudo cat /var/lib/gilbom/releases/current.sha)"
sudo jq . \
  "/var/lib/gilbom/releases/$release_sha/release-manifest.json"
curl -fsS https://i15d101.p.ssafy.io/healthz
curl -fsS https://i15d101.p.ssafy.io/api/health
```

Release Manifest에는 다음 증거가 있어야 한다.

- `releaseSha`가 CI가 전달한 SHA와 일치
- `approval.approvedBy == "jenkins-production-auto-cd"`
- `flyway.migrationCount == 0`
- `flyway.approvalRequired == false`
- `artifact.signature.status == "PASS"`
- 네 health 결과가 모두 `PASS`

자동 배포가 실패하면 Jenkins console, 보관된 preflight·Manifest,
`/var/lib/gilbom/releases/<sha>`의 Rollback 또는 수동 복구 증거와 Mattermost
알림을 함께 확인한다.
