# 서명된 release source tree 배포 설계

## 문제와 목표

기존 Jenkins release는 image archive와 `compose.prod.yaml`만 운영 host에
승격했다. 그래서 container와 Compose는 새 SHA로 배포돼도
`/opt/gilbom/deploy`의 script·monitoring config·runbook은 이전 revision에
남았다. `install-day10-host.sh`가 운영 host에 없었던 직접 원인이다.

목표는 배포된 image, Compose, 운영 source tree를 하나의 full commit SHA와
하나의 서명된 release manifest에 묶는 것이다. 운영 Secret과 환경 파일은
release content에 넣지 않는다.

## 결정

### 1. 별도 수동 archive가 아니라 기존 CI release의 열세 번째 파일로 만든다

`scripts/create-release-source-archive.sh`가 checked-out HEAD와 정확히 같은
`RELEASE_SHA`에서 `gilbom-web-<SHA>.tar.zst`를 만든다. archive는 Git이 추적하는
`web/` tree만 포함한다.

이 파일의 SHA-256은 `build-metadata.json`에 기록되고 `SHA256SUMS`에 포함된다.
기존 전체 manifest Cosign 서명이 source archive까지 보호하므로 source만을 위한
두 번째 서명 키나 별도 신뢰 경로는 두지 않는다.

### 2. 운영 상태와 release content를 분리한다

다음 값은 host 상태이므로 source archive에 들어가면 안 된다.

- `/opt/gilbom/deploy/runtime.env`
- `/opt/gilbom/deploy/image.env`
- `/opt/gilbom/secrets`의 실제 payload
- Docker named volume과 backup

CI는 future commit에서 이 파일들이 추적되더라도 archive 생성을 거부한다. 운영
배포기는 전개한 tree를 다시 검사하고 현재 `runtime.env`와 `image.env`를 새 tree에
복사한다. 같은 정책을 CI와 root 경계에서 각각 검증하는 이유는 CI workspace와
운영 host를 서로 다른 신뢰 경계로 보기 때문이다.

### 3. `/opt/gilbom/deploy` 안에 덮어쓰지 않고 sibling staging 후 교체한다

archive를 기존 tree 위에 바로 풀면 중간 실패 시 서로 다른 revision의 파일이
섞인다. 배포기는 `/opt/gilbom/.deploy-source-<SHA>.*`에 먼저 전개하고 다음을
검사한다.

`git archive`가 directory를 `0775`로 기록하는 경우가 있으므로 root tar의
permission 보존 옵션은 사용하지 않는다. `umask 022`와
`--no-same-permissions`로 directory `0755`, 일반 파일 `0644`, 실행 파일
`0755` 경계로 정규화한 뒤 unsafe mode가 남지 않았는지 다시 검사한다.

- `compose.prod.yaml`, `compose.monitoring.yaml`,
  `infra/scripts/install-day10-host.sh` 존재
- archive의 `compose.prod.yaml`이 독립 release 파일과 byte-for-byte 일치
- symlink와 특수 파일 없음
- setuid/setgid/sticky 또는 group/world writable entry 없음
- runtime 환경 파일과 Secret payload 없음

검사를 통과한 tree만 같은 filesystem의 rename으로 활성화한다.

### 4. source tree는 runtime 성공 경계에서 활성화한다

image load, migration, container health, MQTT와 내부 HTTPS 검증이 끝나기 전에는
현재 source tree를 유지한다. 검증이 성공하면 새 tree를 활성화하고
`.release-source.sha`를 기록한 뒤 Release Manifest와 `current.sha`를 쓴다.

source 교체 후 Manifest 기록 등이 실패하면 기존 source tree를 먼저 복원하고
기존 container rollback 경로를 실행한다. 성공한 뒤에만 이전 tree를 제거한다.

## 감사 증거

배포 전 `deploy-preflight.json`에는 source archive 이름과 SHA-256이 들어간다.
배포 후 `release-manifest.json`에는 같은 값과 활성 marker 경로가 들어간다.
Jenkins evidence 검증은 두 문서의 source SHA-256이 같은지 확인한다.

운영 확인:

```sh
release_sha="$(sudo cat /var/lib/gilbom/releases/current.sha)"
test "$(sudo cat /opt/gilbom/deploy/.release-source.sha)" = "$release_sha"
sudo jq -e --arg sha "$release_sha" '
  .releaseSha == $sha
  and (.source.sha256 | test("^[0-9a-f]{64}$"))
  and .source.activeTreeMarker == "/opt/gilbom/deploy/.release-source.sha"
' "/var/lib/gilbom/releases/$release_sha/release-manifest.json"
```

## 최초 전환

구 운영 helper는 열두 파일만 허용하므로 새 CI보다 먼저 한 번 갱신해야 한다.
정확한 설치 명령은 [`cd-automation.md`](cd-automation.md)의
“새 Artifact 형식 최초 반영”을 따른다. 전환 이후 source archive를 수동 SFTP로
보내거나 `/opt/gilbom/deploy` 위에 직접 푸는 절차는 사용하지 않는다.
