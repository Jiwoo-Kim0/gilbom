# Stage 12 manual production deployment Job

`gilbom-deploy`는 검증된 full commit SHA 하나를 운영에 적용하는 수동 Jenkins
Pipeline이다. CI Job과 분리하며 자동 trigger를 연결하지 않는다.

## Artifact staging 경계

Stage 9 CI가 이미 다음 EC2 immutable Artifact staging에 아홉 개 release 파일을
전송하고 root 소유로 승격한다.

```text
/opt/gilbom/artifacts/<40자리-full-sha>/
```

따라서 배포 Job은 같은 대용량 archive를 다시 복사하지 않는다. 승인 전에 해당
staging의 소유권·쓰기 권한·파일 수·전체 checksum과 metadata를 재검증하고, 승인
후 표시했던 preflight SHA-256을 staging Artifact와 결속한다. Artifact가 없거나
CI에서 승격된 내용과 다르면 승인 화면까지 도달하지 않는다.

## EC2 Job helper 설치

Stage 10·11이 완료된 EC2의 최신 repository에서 관리자가 한 번 실행한다.

```sh
sudo sh \
  /opt/gilbom/app-git/web/infra/scripts/install-production-deploy-job-entrypoints.sh
```

설치되는 root 명령은 다음과 같다.

| 명령 | 용도 |
|---|---|
| `/usr/local/sbin/gilbom-deploy-preflight` | Artifact와 현재 운영·Flyway 상태를 승인용 JSON으로 고정 |
| `/usr/local/sbin/gilbom-record-deploy-approval` | Jenkins 승인자와 시간을 exact preflight hash에 결속 |
| `/usr/local/sbin/gilbom-release-manifest` | 지정한 SHA의 Manifest만 읽어 Jenkins로 반환 |
| `/usr/local/sbin/gilbom-deploy` | 승인 검증 후 실제 root 배포 |

Artifact 운영 계정 `ubuntu`에는 앞의 세 helper만 별도 sudo allowlist로 허용한다.
`deploy` 계정의 Stage 10 sudoers는 계속 아래 한 줄뿐이다.

```text
deploy ALL=(root) NOPASSWD: /usr/local/sbin/gilbom-deploy
```

따라서 `deploy` 계정은 자기 승인을 만들거나 Manifest를 임의로 읽을 수 없다.

설치 성공 결과:

```text
Production deploy Job host verification: PASS
Production deploy Job entrypoint installation: PASS
```

## Jenkins Job 생성

`New Item → Pipeline`에서 Job 이름을 `gilbom-deploy`로 만든다.

Pipeline 설정:

| 항목 | 값 |
|---|---|
| Definition | `Pipeline script from SCM` |
| SCM | `Git` |
| Repository URL | `https://lab.ssafy.com/s15-webmobile3-sub1/S15P11D101.git` |
| Credentials | `gitlab-scm-read` |
| Branch Specifier | `*/master` |
| Script Path | `web/Jenkinsfile.deploy` |

자동 SCM polling, webhook build trigger와 정기 실행은 모두 끈다. Job의
`Build` 권한은 운영 배포 요청자에게만 주고 Anonymous 권한은 제거한다.

Pipeline의 `PRODUCTION_DEPLOY_APPROVERS`는 Jenkins 보안 realm의 실제 사용자
ID 또는 외부 그룹명이다. 현재 기준값은 `김채운`이다. 다른 승인자를 추가할
때는 쉼표로 구분하며, 공유 계정이나 `anonymous`는 넣지 않는다. Jenkins
관리자는 이 제한과 별개로 Pipeline input을 처리할 수 있으므로 관리자 계정도
개인별 계정을 사용한다.

필요 Credential:

| ID | 용도 |
|---|---|
| `gitlab-scm-read` | trusted `master`와 입력 SHA 확인 |
| `gilbom-artifact-upload-ssh` | `ubuntu` preflight·승인 기록·Manifest 회수 |
| `gilbom-artifact-known-hosts` | 검증된 EC2 SSH host key |
| `gilbom-production-deploy` | forced-command `deploy` 계정으로 root 배포 실행 |

기본 Jenkins→EC2 주소는 `host.docker.internal:22`, 외부 smoke 주소는
`https://i15d101.p.ssafy.io`다. Jenkins 배치가 달라질 때는
`Jenkinsfile.deploy`의 고정 운영 환경값을 코드 리뷰로 변경한다. 사용자가 Build
parameter로 host, URL, Compose 또는 Secret 경로를 전달하게 만들지 않는다.

## 실행과 승인 화면

`Build with Parameters`에서 `FULL_COMMIT_SHA`에 40자리 소문자 SHA를 입력한다.
해당 SHA가 `origin/master` 이력이고 Stage 9 immutable Artifact가 존재해야 한다.

승인 화면은 다음을 모두 표시한다.

- Commit SHA
- Backend·Frontend Image ID
- Image archive SHA-256
- Critical·High·승인 예외·미승인 High 개수
- 현재 운영 SHA
- 실패 시 rollback 대상 SHA
- 현재·대상 Flyway version
- 적용 예정 migration 파일명과 각 SHA-256
- Migration 호환성 판정과 사유

`BLOCKED_*` 판정, Critical 또는 미승인 High가 있으면 Jenkins input을 만들지 않고
실패한다. 승인 버튼은 `PRODUCTION_DEPLOY_APPROVERS`에 포함된 Jenkins 인증
사용자만 누를 수 있다. Jenkins가 반환한 사용자 ID와 승인 UTC 시간을 JSON으로
전송하며, root helper는 표시된 preflight checksum과 일치할 때만 immutable 승인
기록을 만든다.

승인 뒤 실제 운영 변경 전에 기술적 오류가 발생하면 같은 SHA의 재실행은 기존
preflight와 승인 파일을 덮어쓰지 않는다. Artifact, 현재 운영 SHA, Flyway version,
migration plan이 승인 당시와 동일한 경우에만 exact 승인 증거를 재사용하며, 하나라도
달라졌으면 재시도를 차단한다.

승인 이후 Pipeline은 다음 순서로 실행한다.

1. 승인된 preflight hash를 EC2 immutable Artifact staging과 결속
2. `gilbom-production-deploy` Credential로 고정 root 배포기 실행
3. root 배포 PASS 확인
4. 정상 CA·hostname 검증을 사용하는 외부 `/healthz`, `/api/health` HTTPS smoke
5. root Release Manifest 회수
6. preflight·승인자·Image ID·Manifest·HTTPS 결과 교차 검증
7. `.artifacts/deploy/**`를 Jenkins Artifact로 fingerprint 보관

Release Manifest의 `approval`에는 `approvedBy`, `approvedAtUtc`,
`preflightSha256`이 기록된다. Jenkins 보관물에는 다음 파일이 포함된다.

```text
preflight.json
preflight.sha256
approval-summary.txt
approval-input.json
approval-record.json
external-https-smoke.json
release-manifest.json
```

Secret 값, SSH private key와 Jenkins 임시 Credential 경로는 보관하지 않는다.
