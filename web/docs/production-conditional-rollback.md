# Stage 13 conditional production rollback

운영 배포 실패를 Flyway 변화 여부로 분류한다. 판단과 1차 containment는 Jenkins가
아니라 고정 root 배포기가 수행한다. Jenkins 연결이 끊겨도 Frontend 노출 차단과
증거 보존이 EC2에서 계속 실행되어야 하기 때문이다.

## A. Flyway 변화 없음

`image.env` 전환 이후 배포가 실패하면 배포기는 운영 Flyway를 즉시 재조회한다.
배포 직전 version과 같을 때만 다음 자동 Rollback을 허용한다.

1. Frontend 중지
2. 이전 Backend·Frontend image가 없으면
   `/opt/gilbom/artifacts/<previous-sha>/images.tar.zst`를 checksum과 image config
   ID까지 검증한 뒤 다시 `docker load`
3. `image.env`를 previous SHA로 원자 복원
4. 이전 Backend 기동과 readiness 확인
5. 이전 Frontend 기동과 health 확인
6. Frontend 내부 HTTPS `/api/health` smoke
7. `/var/lib/gilbom/releases/<failed-sha>/rollback-manifest.json` 기록

실패한 배포의 Jenkins 결과 자체는 FAILURE다. Manifest의
`AUTOMATIC_ROLLBACK_SUCCEEDED`가 서비스 복구 성공을 별도로 증명한다.

## B. Flyway 변화 있음, 이전 Backend 호환 승인

Flyway가 변했으면 자동 image Rollback은 하지 않는다. 운영자는 별도
`gilbom-rollback` Jenkins Job에서 실패 SHA를 지정한다.

Job은 `manual-recovery-required.json`, 현재 Flyway, current SHA, rollback 대상
Artifact와 image ID를 다시 고정하여 보여준다. 승인 버튼은 다음 내용을 명시적으로
확인하는 두 번째 승인이다.

- 이전 Backend가 현재 DB schema와 호환됨
- migration undo를 하지 않음
- backup DB 자동 복원을 하지 않음

승인 기록은 exact rollback preflight SHA-256과 결속된다. 승인 후 Job은 Stage 10의
동일 forced-command SSH Credential로 다음 명령만 실행한다.

```text
sudo --non-interactive /usr/local/sbin/gilbom-deploy <previous-full-sha>
```

root 배포기는 `current.sha`와 요청 SHA가 같더라도 `image.env`가 contained failed
SHA를 가리키고, manual recovery와 exact compatibility 승인이 모두 일치할 때만
복구 모드로 진입한다. 이전 Artifact 검증, DB·Volume backup, Backend readiness,
Frontend health와 HTTPS smoke를 수행하고
`approved-rollback-manifest.json`을 남긴다.

## C. Flyway 변화 있음, 호환성 미확인

기본값은 항상 fail-closed다.

1. Frontend 중지
2. 신규 Backend와 DB 상태 유지
3. 실패 container log, DB·Volume backup, release Artifact 보존
4. `manual-recovery-required.json` 기록
5. 운영자에게 `gilbom-rollback` 호환성 검토 요청

Flyway 재조회 실패도 “변화 없음”으로 추정하지 않고 C로 분류한다.

## 격리 검증

`scripts/test-production-conditional-rollback-integration.sh`는 `/opt`, `/var/lib`,
`/run`, `/usr/local`을 임시 mount namespace로 격리한 환경에서 다음을 검증한다.

- Flyway 무변경 실패의 이전 Artifact 재로딩과 자동 Rollback
- Flyway 변경 실패의 Frontend 차단과 증거 보존
- 2차 승인 없이는 previous SHA 실행 거부
- exact preflight 승인 후 동일 root 배포기로 이전 Backend 복구
- 모든 경로에서 migration undo와 backup DB 자동 복원 미실행

## EC2 설치

Stage 13 코드가 master에 반영된 뒤 EC2에서 최신 코드를 받은 다음 두 설치기를
다시 실행한다.

```sh
sudo sh \
  /opt/gilbom/app-git/web/infra/scripts/install-production-deploy-entrypoint.sh

sudo sh \
  /opt/gilbom/app-git/web/infra/scripts/install-production-deploy-job-entrypoints.sh
```

첫 설치기는 root 배포기와 `/opt/gilbom/rollback-approvals` 경계를 준비한다.
두 번째 설치기는 rollback preflight·승인·Manifest helper와 `ubuntu` 전용 sudo
allowlist를 설치한다. `deploy` 계정 sudoers는 여전히 `gilbom-deploy` 한 줄뿐이다.

## Jenkins Job

`gilbom-rollback` Pipeline Job을 만들고 기존 `gilbom-deploy`와 같은 SCM,
Credential, `*/master`를 사용한다. Script Path만 다음으로 설정한다.

```text
web/Jenkinsfile.rollback
```

SCM polling, webhook, 정기 Trigger는 모두 끈다. 실행 parameter는
`FAILED_RELEASE_SHA` 하나이며, `manual-recovery-required`가 없는 정상 release나
Flyway 변화가 없는 실패 release는 승인 화면 전에 거부된다.
