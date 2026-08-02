# 보안 도구·취약점·Artifact 정책

기준일: 2026-08-02

## 1. 확정 도구

| 경계 | 도구 | 고정 버전 | 차단 기준 |
|---|---|---:|---|
| Git 전체 이력·source workspace | Gitleaks | 8.29.0 | Secret 1건 이상 |
| OpenAPI | Redocly CLI | 2.40.0 | lint error 1건 이상 |
| dependency·image·SBOM·CVE | Trivy | 0.70.0 | 아래 취약점 정책 위반 |
| release archive | zstd | 1.5.7 | 생성 또는 해제 실패 |
| archive 무결성 | SHA-256 | 시스템 `sha256sum` | 전송 전·후 hash 불일치 |

실행 image는 `infra/security/tool-versions.env`에서 tag와 digest를 함께 고정한다.
Jenkins custom image에는 Gitleaks와 Trivy binary, zstd, OpenSSH client를 포함한다.
custom image를 아직 재빌드하지 않은 agent에서도 같은 digest의 공식 container를
fallback으로 사용하므로 검사 자체를 선택적으로 건너뛰지 않는다.

Trivy 0.70.0은 0.69.4 공급망 사고 이후 공개된 버전으로 고정했다. 버전 변경은
공식 release와 image digest를 확인하고 이 파일, Jenkins Dockerfile, 버전 검증
계약을 같은 변경에서 갱신한다.

Cosign은 현재 Registry digest를 배포 단위로 쓰지 않고 local Docker archive만
전달하므로 필수 gate에 넣지 않는다. Registry를 도입하면 digest reference,
keyless 또는 KMS 서명, admission 검증 주체를 확정한 뒤 추가한다.

## 2. Secret과 OpenAPI 정책

`scripts/verify-source-security.sh`는 Jenkins가 임시 Secret을 만들기 전에 action
단위로 실행한다.

1. `history`: repository root의 모든 commit과 현재 branch 외 refs를 Gitleaks로
   검사한다.
2. `workspace`: 현재 `web` checkout을 별도로 검사한다.
3. `openapi`: Redocly `recommended` 규칙으로 `docs/openapi-v1.yaml`을 lint한다.
4. `backend-dependencies`, `frontend-dependencies`: 각 dependency Trivy JSON과
   CycloneDX SBOM을 별도로 만든다.
5. `dependency-policy`: 두 보고서에 Critical/High/예외 정책을 함께 적용한다.

Gitleaks finding은 baseline 또는 취약점 예외로 무시할 수 없다. 생성 build와
`.artifacts`만 source 경계 밖이라 path allowlist로 제외한다. ignored `.env`,
`secrets/`, 로컬 MQTT key 저장소도 workspace 검사 시점에 존재하면 차단한다.
Jenkins에서는 이 검사를 임시 Secret 생성 전에 실행한다. Java의 PEM boundary
문자열은 private-key rule의 해당 문자열 행만 정밀 제외하며 같은 파일의 다른
Secret rule은 계속 적용된다.

Redocly warning은 artifact에 남기되 error만 차단한다. 현재 warning은 라이선스
메타데이터와 미래 endpoint용 미사용 공통 component이며 API 유효성 오류는 아니다.

보고서는 모두 redaction된 `.artifacts/source-security/`에 저장한다. 원문 Secret은
stdout, Jenkins artifact, 예외 파일에 기록하지 않는다.

## 3. 취약점 차단과 예외

정본은 `infra/security/vulnerability-exceptions.yaml`이다.

- Critical: 예외가 있어도 항상 차단한다.
- High: CVE/GHSA와 image role 또는 정확한 package가 일치하는 유효 예외가 필요하다.
- Medium·Low: 보고서와 SBOM에 남기고 release gate는 통과한다.
- 만료일이 오늘보다 과거인 예외: 현재 finding과 무관하게 차단한다.
- 필드 누락, 중복, placeholder 담당자, 20자 미만 사유, 잘못된 날짜: fail-closed.
- 만료일 당일까지 유효하며 날짜 판정은 UTC를 사용한다.

예외 형식:

```yaml
schemaVersion: 1
exceptions:
  - cve: CVE-2026-12345
    target: package:group:artifact
    owner: backend-team
    reason: Upstream patch 적용 일정을 포함한 승인 사유를 기록한다.
    expiresOn: 2026-08-31
```

`target`은 `image:backend`, `image:frontend`, `image:dependency` 또는 Trivy
`PkgName`과 정확히 일치하는 `package:<name>`만 허용한다. 넓은 wildcard와
무기한 예외는 허용하지 않는다. 정책 엔진의 Critical·High·만료·형식 오류
fail-closed 동작은 `scripts/test-security-policy.py`가 합성 데이터로 회귀 검증한다.

Image build 뒤 `scripts/scan-image-security.sh`가 Backend와 Frontend Docker
archive를 Trivy로 검사하고 다음 증거를 만든다.

- `backend|frontend.trivy.json`
- `backend|frontend.sbom.cdx.json`
- `policy-summary.json`
- `scanner-status.txt`

기존 `verify-production-images.sh`의 non-root·layer·Secret·runtime·log 검증은
유지하고, 선택적 Docker Scout 경로만 이 필수 Trivy 정책으로 교체했다.

Release 후보에서는 `IMAGE_SCANNER_MODE=required`만 허용한다.
`scripts/prepare-release-security-artifacts.sh`가 정책을 다시 평가한 다음
`release/sbom/{backend,frontend}.sbom.json`과
`release/vulnerabilities/{backend,frontend}.txt`로 정규화한다. 스캐너 상태가
Trivy가 아니거나 네 파일 중 하나라도 비어 있으면 실패한다.

## 4. zstd archive와 SSH/root 승격

clean worktree의 현재 40자리 SHA가 release ID다.

```sh
sh scripts/build-release-artifacts.sh
```

이 명령은 Git source와 Backend·Frontend·Jenkins image를 `.tar.zst`로 만들고
`release.properties`, `SHA256SUMS`를 생성한 뒤 로컬에서 즉시 재검증한다.
기존 release 디렉터리를 덮어쓰지 않으며 모든 파일 mode는 `0600`이다.

운영 host에는 승격 명령을 root 소유로 한 번 설치한다.

```sh
sudo install -o root -g root -m 0755 \
  infra/scripts/promote-release-artifacts.sh \
  /usr/local/sbin/gilbom-promote-release-artifacts
```

배포용 계정의 sudoers는 위 명령 하나만 비대화형으로 허용한다. SSH host key는
사전에 별도 파일로 수집하고 fingerprint를 운영 담당자가 확인한다. `accept-new`,
host-key 검사 해제, password를 명령행에 넣는 방식은 허용하지 않는다.

```sh
release_sha="$(git rev-parse HEAD)"
KNOWN_HOSTS_FILE="$PWD/infra/known_hosts.production" \
  sh scripts/transfer-release-artifacts.sh \
    ".artifacts/release/$release_sha" \
    ubuntu@production-host
```

전송 명령은 로컬 SHA-256 검증, StrictHostKeyChecking을 적용한 SFTP 기반 `scp`,
원격 staging SHA-256 검증 순서로 실행한다. 이어 root 승격 명령이 허용된 파일명과
일반 파일·symlink 경계를 다시 검사하고, copy 전후 SHA-256을 각각 검증한다.
성공한 디렉터리는 `/opt/gilbom/artifacts/releases/<40자리 SHA>`에
`root:root`, directory `0750`, file `0640`으로 원자적으로 승격한다. 같은 SHA를
덮어쓰지 않는다.

Jenkins가 전송을 자동화하려면 별도 SSH private-key Credential, 검증된
`known_hosts`, 대상 계정의 제한된 sudoers가 필요하다. 이 외부 승인 정보가
확정되기 전까지 CI는 archive 생성·검사까지만 담당하며 운영 전송은 자동 실행하지
않는다.

CI의 통합 image archive는 기존 EC2 수동 배치 bundle과 별도 경계다. 모든 test와
보안 검사가 PASS인 full-SHA Backend·Frontend image만 `release/images.tar.zst`로
FIFO streaming하고 `images.tar.zst.sha256`을 즉시 확인한다. 이어 provenance를
`release/build-metadata.json`으로 만들고 `jq empty`로 검사한다.

최종 `release/SHA256SUMS`는 Archive뿐 아니라 metadata, SBOM, 취약점 보고서와
정책 요약까지 모두 보호한다. Artifact 디렉터리는 symlink·Secret형 파일·실제 CI
Secret 값·provider token/private-key 패턴을 다시 검사한 뒤에만 보존한다.

## 5. CI Artifact incoming과 원자적 승격

기존 `.artifacts/release/<SHA>` 수동 배치 bundle과 Stage 4~8의 `release/`는
서로 다른 Artifact다. CI release는 모든 gate가 성공한 다음
`scripts/upload-ci-release-artifacts.sh`가
`/opt/gilbom/artifacts/.incoming/<SHA>/`로만 전송한다. Jenkins Credential의
private key와 검증된 `known_hosts`를 사용하며 전송 전후 SHA-256을 확인한다.

Jenkins 수동 승인 이후
`/usr/local/sbin/gilbom-promote-ci-release-artifacts`가 root로 실행된다. 이
명령은 Git checkout에서 직접 실행하지 않고
`infra/scripts/promote-ci-release-artifacts.sh`를 root 소유 mode `0755`로
설치한 사본이다. 검증된 incoming 디렉터리를 동일 filesystem의
`/opt/gilbom/artifacts/<SHA>/`로 atomic rename하고 모든 entry를 root 소유
읽기 전용으로 만든다. 기존 SHA는 `SHA256SUMS`와 양쪽 실제 파일이 동일할 때만
재사용한다.

## 6. 실행

```sh
sh scripts/verify-security-tooling-policy.sh
sh scripts/verify-source-security.sh all

docker compose -f compose.build.yaml build backend frontend
IMAGE_SCANNER_MODE=required sh scripts/verify-production-images.sh
```

`IMAGE_SCANNER_MODE=off`는 Secret/layer 검증기의 합성 self-test와 Trivy 증거가 이미
승인된 offline 운영 host 검사에만 허용한다. CI와 release 후보에서는 `required`다.
