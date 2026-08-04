# Cosign Blob 서명·운영 검증

기준일: 2026-08-03

## 신뢰 경계

길봄은 Container Registry가 아니라 릴리스 파일을 EC2로 전송한다. 따라서 image
tag를 Registry에서 서명하는 대신 실제 배포 입력을 Cosign `sign-blob`으로
서명한다. 이미지 archive는 독립 서명을 유지하고, Compose를 포함한 전체 payload는
`SHA256SUMS` 서명으로 함께 보호한다.

- Cosign: `v3.1.2`
- 버전·Linux 바이너리 Digest 정본: `infra/cosign/version.env`
- 이미지 서명 대상/Bundle:
  `release/images.tar.zst`, `release/images.tar.zst.sigstore.json`
- 전체 manifest 서명 대상/Bundle:
  `release/SHA256SUMS`, `release/SHA256SUMS.sigstore.json`
- `SHA256SUMS` payload: image archive·image Bundle·SBOM·CVE 결과·build metadata·
  `compose.prod.yaml`
- Private key: Jenkins Secret File Credential에만 저장
- Password: Jenkins Secret Text Credential에만 저장
- Public key: EC2 `/etc/gilbom/cosign-release.pub`
- 검증 시점: root Artifact 승격, 배포 preflight, `docker load` 직전,
  이전 Artifact 자동 Rollback load 직전

외부 Rekor와 TSA를 사용하지 않는 폐쇄형 key-pair 운영이므로 Bundle에는 공개
transparency log proof가 없다. `--insecure-ignore-tlog`는 이 부재만 명시적으로
허용하며, archive 서명은 EC2에 고정한 공개키로 계속 검증한다.

## 1. 키 생성

운영 EC2나 Git checkout이 아닌 별도 관리자 단말에서 한 번 생성한다.

```sh
cosign generate-key-pair --output-key-prefix gilbom-release
```

충분히 긴 Password를 입력하면 다음 두 파일이 생성된다.

```text
gilbom-release.key
gilbom-release.pub
```

`gilbom-release.key`는 EC2, Git, Mattermost 또는 Jenkins checkout/release
디렉터리에 수동 복사하지 않는다. Jenkins Credential이 build 동안 만드는
일회성 Secret File 경로에서만 읽으며, 별도 백업은 관리자 단말의 암호화된 비밀
저장소에만 둔다.

## 2. Jenkins Credential

`Manage Jenkins → Credentials → System → Global credentials`에서 생성한다.

| ID | Kind | 값 |
|---|---|---|
| `gilbom-cosign-private-key` | Secret file | `gilbom-release.key` |
| `gilbom-cosign-password` | Secret text | 키 생성 시 Password |

Pipeline은 두 Credential을 archive 서명 Stage에서만 바인딩하고 `set +x` 상태로
사용한다. Private key와 Password는 Release Artifact·증거 JSON·Console에 넣지
않는다.

## 3. Jenkins Cosign 설치

Custom Jenkins image는 공식 GitHub Release에서 고정 바이너리를 내려받고
SHA-256을 확인한 뒤 `/usr/local/bin/cosign`에 설치한다.

```sh
cd /opt/gilbom/deploy
sudo docker compose -f compose.jenkins.yaml build --pull jenkins
sudo docker compose -f compose.jenkins.yaml up -d jenkins
sudo docker compose -f compose.jenkins.yaml exec jenkins cosign version
```

출력의 `GitVersion`이 `v3.1.2`여야 한다.

## 4. EC2 공개키와 검증기 설치

공개키만 EC2 임시 경로로 전달한 뒤 관리자 권한으로 설치한다.

```sh
sudo sh \
  /opt/gilbom/deploy/infra/scripts/install-cosign-release-verifier.sh \
  /tmp/gilbom-release.pub

sudo stat -c '%U:%G:%a %n' \
  /usr/local/bin/cosign \
  /etc/gilbom/cosign-release.pub

sudo /usr/local/sbin/gilbom-verify-production-deploy-job-host
```

기대 mode는 Cosign `root:root:755`, 공개키 `root:root:444`다. 설치기는 host
architecture별 고정 Digest와 버전을 확인하고 유효한 PEM 공개키만 설치한다.
이후 root deploy entrypoint 설치기를 다시 실행해 새 검증 계약을 반영한다.

```sh
sudo sh \
  /opt/gilbom/deploy/infra/scripts/install-production-deploy-entrypoint.sh
sudo sh \
  /opt/gilbom/deploy/infra/scripts/install-production-deploy-job-entrypoints.sh
```

## 5. CI와 배포 판정

CI는 SBOM·CVE 정책을 통과한 뒤 archive와 Compose가 포함된 manifest를 만들고,
두 서명 각각에 다음 세 검증을 수행한다.

1. 정확한 공개키로 정상 Bundle 검증 PASS
2. archive 뒤에 데이터를 추가한 변조 입력 거부 PASS
3. 임시로 생성한 다른 공개키 거부 PASS

PASS 증거는 `.artifacts/cosign/summary.json`,
`.artifacts/cosign-manifest/summary.json`,
`.artifacts/ci-results/cosign-blob-signature.status`,
`.artifacts/ci-results/cosign-release-manifest.status`다. 전체 manifest Bundle
자체는 순환 참조를 피하기 위해 `SHA256SUMS` 항목에서 제외되며, root 승격기가
고정 공개키로 Bundle과 `SHA256SUMS`를 먼저 검증한다.

EC2 root 승격기와 배포기는 전체 manifest 서명, payload SHA-256, 이미지 archive
서명을 순서대로 검증한다. 서명 실패 시 Artifact 승격 또는 preflight가 실패하며,
`docker load`, `image.env` 교체, Compose 변경은 실행되지 않는다. 자동 Rollback이
이전 archive를 다시 load해야 하는 경우에도 같은 이미지 서명 검증을 통과해야 한다.

## 6. 키 회전

현재 배포기는 단일 공개키를 고정한다. 공개키를 교체하면 이전 키로 서명된
Rollback Artifact를 검증할 수 없으므로, `previous.sha`와 보존 중인 Rollback
Artifact의 서명 정책을 먼저 검토해야 한다. 다중 키 ID·유효기간을 구현하기 전에는
배포 사이에 임의로 키를 회전하지 않는다.
