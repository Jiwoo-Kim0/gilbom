# Stage 15 SCM Polling, 운영 증거와 Mattermost 알림

기준일: 2026-08-02

Stage 15는 `gilbom-ci`만 5분 간격으로 SCM 변경을 확인하고, 배포·Rollback Job은
계속 full SHA 입력과 Jenkins 인증 사용자 승인으로만 실행한다. CI/CD 결과는
Mattermost Incoming Webhook으로 전송한다. 실패 알림은 첫 미완료 gate, 로컬 재현
명령, Jenkins 콘솔과 보안 산출물 링크를 포함한다.

## 1. Jenkins Trigger

`web/Jenkinsfile`에만 다음 Declarative Pipeline trigger가 있다.

```groovy
triggers {
    pollSCM('H/5 * * * *')
}
```

Jenkins의 `gilbom-ci` Job은 기존처럼 다음을 사용한다.

| 항목 | 값 |
|---|---|
| Definition | `Pipeline script from SCM` |
| Branch Specifier | `*/master` |
| Script Path | `web/Jenkinsfile` |
| Lightweight checkout | 해제 |
| UI Build Trigger | 모두 해제 |

`H/5`는 매 5분 구간 안에서 Jenkins가 정한 시각에 polling한다. 5분마다 무조건
빌드하는 cron이 아니라, remote `master`가 바뀐 경우에만 새 CI가 시작된다.
Jenkinsfile trigger는 해당 Jenkinsfile을 한 번 실행한 뒤 Job 설정에 반영된다.

다음 Job의 UI Build Trigger는 모두 해제하고 Jenkinsfile에도 `triggers`를 두지 않는다.

- `gilbom-deploy`
- `gilbom-rollback`
- `gilbom-rollback-drill`

운영 배포는 `FULL_COMMIT_SHA`, Rollback은 `FAILED_RELEASE_SHA`와 각각의 수동 승인
절차를 계속 요구한다.

## 2. Mattermost 알림 계정

한 방향 결과 알림에는 Bot API token보다 Incoming Webhook이 더 작은 권한 경계다.
Mattermost는 Webhook 글에도 `BOT` 표시를 붙인다. 대화형 명령, 스레드 갱신,
Mattermost API 조회가 나중에 필요할 때만 전용 Bot Account로 확장한다.

Mattermost에서 다음을 수행한다.

1. 알림 전용 비공개 또는 공개 채널 `gilbom-cicd`를 만든다.
2. `Product menu → Integrations → Incoming Webhooks`를 연다.
3. `Add Incoming Webhook`을 누르고 이름을 `gilbom-cicd`, 채널을 위 채널로 정한다.
4. 생성된 `https://<mattermost>/hooks/<secret>` URL을 한 번만 복사한다.

`Integrations`가 보이지 않으면 Mattermost System Admin이
`System Console → Integrations → Integration Management → Enable incoming
webhooks`를 켜야 한다. Webhook URL은 그 값을 아는 누구나 글을 쓸 수 있는
Secret이므로 Git, Jenkinsfile, 채팅, 빌드 parameter에 넣지 않는다.

Jenkins에서 `Manage Jenkins → Credentials → System → Global credentials →
Add Credentials`를 연다.

| 항목 | 값 |
|---|---|
| Kind | `Secret text` |
| Secret | Mattermost Incoming Webhook 전체 URL |
| ID | `gilbom-mattermost-webhook` |
| Description | `Gilbom CI/CD Mattermost incoming webhook` |

네 Jenkinsfile은 빌드 `post`에서 이 Credential을 일시 binding한다. HTTPS
certificate를 정상 검증하고 Mattermost가 HTTP 200과 `ok`를 반환해야 전송
`PASS`가 된다. Credential이 아직 없거나 Mattermost가 일시 장애여도 원래
CI/CD 결과를 뒤집지 않고 `delivery.status=SKIPPED`를 보관한다.

실패만 채널 전체에 알리고 싶다면 Jenkins 전역 환경변수
`MATTERMOST_FAILURE_MENTION=@channel`을 설정한다. 허용값은 `@channel`,
`@here`, `@all`과 Mattermost의 `<!...>` 동등 표현뿐이다.

## 3. 개발자 실패 로그 읽기 권한

Mattermost 알림의 `BUILD_URL/console` 링크가 팀원에게 열리도록 Jenkins의
`Manage Jenkins → System → Jenkins Location → Jenkins URL`을 팀에서 접근 가능한
HTTPS 주소로 설정한다. 현재 `compose.jenkins.yaml`의 `127.0.0.1:8081` binding은
그대로 유지한다. 8081을 인터넷에 직접 공개하지 말고 조직 VPN 또는 인증·TLS가
있는 reverse proxy를 통해서만 연결한다.

Matrix Authorization 또는 동등한 Role 전략에서 일반 개발자 그룹에는 다음
읽기 권한만 부여한다.

- `Overall/Read`
- `Job/Read`
- `View/Read`

배포 담당자가 아닌 개발자에게 `Overall/Administer`, `Job/Configure`,
`Run/Replay`, `Credentials/View`, `Job/Workspace`는 부여하지 않는다.
필요한 사용자에게만 별도로 `Job/Build`를 부여한다. `Job/Read`가 있으면 해당
Job의 build, console과 보관 Artifact를 확인할 수 있다.

알림은 다음 정보를 제공한다.

- 결과, Job/Build 번호와 full commit SHA
- 성공하지 못한 경우 첫 미완료 gate와 마지막 PASS gate
- 같은 검사를 로컬에서 실행할 재현 명령
- Jenkins 전체 콘솔 링크
- `.artifacts/source-security/`와 결과 요약 Artifact 링크

콘솔 원문은 Mattermost에 복제하지 않는다. 성공 CI의 마지막 gate는 현재 Jenkins
build log 파일을 Gitleaks로 검사하고, 그 build에서 만든 임시 Secret 원문과도
대조한다. 결과는 다음에 보관된다.

```text
.artifacts/jenkins-log-security/gitleaks-jenkins-console.json
.artifacts/jenkins-log-security/status
.artifacts/ci-results/jenkins-log-secret.status
```

Jenkins Credential 값은 기존처럼 `withCredentials`, masking과 `set +x` 경계
안에서만 사용한다.

## 4. 운영 Host 증거 수집기 설치

Stage 15 코드를 `master`에 반영한 뒤 EC2의 기존 repository에서 최신 코드를
fast-forward하고 root-owned 고정 검증기를 설치한다. 이것은 애플리케이션 배포가
아니며 image나 운영 Compose를 변경하지 않는다.

```sh
git -C /opt/gilbom/app-git pull --ff-only origin master

sudo sh \
  /opt/gilbom/app-git/web/infra/scripts/install-stage15-operations-verifier.sh
```

설치기는 다음 읽기 전용 명령만 `ubuntu`에 NOPASSWD로 허용한다.

```text
sudo /usr/local/sbin/gilbom-stage15-host-evidence
```

검증기는 인자를 받지 않고 다음을 fail-closed로 확인해 Secret 없는 JSON만 stdout에
출력한다.

- Jenkins container에 `/var/run/docker.sock` mount 없음
- `tcp://docker-builder:2376`, client certificate와 TLS verify
- `deploy` 계정 Docker group 미가입
- root 소유 `current.sha`, `previous.sha`의 full SHA
- 현재 SHA의 Release Manifest와 네 health 결과
- 설치된 고정 root 배포기의 `docker compose ... --no-build` 계약

EC2에서 증거를 저장한다.

```sh
sudo /usr/local/sbin/gilbom-stage15-host-evidence \
  > /tmp/stage15-host-evidence.json
jq . /tmp/stage15-host-evidence.json
```

Jenkins artifact SSH Credential을 사용하는 환경에서는
`scripts/fetch-stage15-host-evidence.sh`로 같은 JSON을 회수할 수 있다.

## 5. 최종 증거 판정

최신 Stage 15 CI를 한 번 `SUCCESS`로 완료하고 Artifact 승격까지 승인한다. 그
CI 로그의 exact `RELEASE_SHA`로 `gilbom-deploy`를 실행해 운영 배포도
`SUCCESS`로 끝낸다. 최종 판정은 CI SHA와 운영 `current.sha`가 다르면 실패한다.
해당 CI build에서 다음 두 항목을 내려받는다.

- `release/build-metadata.json`
- `.artifacts/ci-results/`

성공한 `gilbom-rollback-drill` build에서
`.artifacts/rollback-drill/summary.json`을 내려받고, 앞 절의
`stage15-host-evidence.json`을 한 디렉터리에 둔다.

```sh
python3 scripts/generate-stage15-final-evidence.py \
  --ci-metadata stage15-input/build-metadata.json \
  --ci-results stage15-input/ci-results \
  --host-evidence stage15-input/stage15-host-evidence.json \
  --rollback-drill stage15-input/rollback-drill-summary.json \
  --output stage15-final-evidence.json
```

출력의 `status`가 `PASS`일 때 다음 요구사항이 모두 입증된다.

- Jenkins mount에 Docker Socket 없음
- DinD TLS 연결
- Checkout이 shallow 아님
- Git history·workspace Secret Scan PASS
- OpenAPI·Backend/Frontend Dependency Scan PASS
- Full SHA Backend·Frontend image tag
- Artifact Checksum PASS
- 통합·운영 `docker compose up --no-build`
- deploy 계정 Docker 그룹 미가입
- Jenkins console Secret Scan PASS
- `current.sha`, `previous.sha`, 현재 Release Manifest 존재
- Migration 없는 자동 Rollback PASS
- Migration 비호환 자동 Rollback 차단과 Frontend 중지 PASS

증거 하나가 없거나 `PASS`가 아니면 최종 JSON을 만들지 않고 실패한다. 정적 계약은
아래 명령으로 매 CI의 Repository Policy에서 자동 검증한다.

```sh
sh scripts/verify-stage15-cicd-operations-contract.sh
```
