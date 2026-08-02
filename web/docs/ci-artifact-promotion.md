# CI Artifact 전송과 원자적 승격

Stage 9는 Stage 4~8의 모든 검사와 `release/SHA256SUMS` 검증이 끝난 뒤에만
실행한다. Jenkins는 최종 경로에 쓸 수 없고 다음 incoming 경계만 사용한다.

```text
/opt/gilbom/artifacts/.incoming/<40자리 release SHA>/
```

Jenkins의 수동 승인 이후, 운영 host에 root 소유로 설치한 승격 명령이 구조와
SHA-256을 다시 검사하고 같은 filesystem 안에서 다음 경로로 rename한다.

```text
/opt/gilbom/artifacts/<40자리 release SHA>/
```

최종 디렉터리와 파일은 모두 `root:root`이고 각각 mode `0550`, `0440`이다.
동일 SHA가 이미 존재하면 incoming과 final의 `SHA256SUMS`가 완전히 같고 양쪽
내용 검증도 성공한 경우에만 기존 디렉터리를 재사용한다. Checksum이 다르면 기존
디렉터리를 수정하거나 교체하지 않고 실패한다.

## EC2 최초 설정

업로드 계정이 `ubuntu`이고 기본 group도 `ubuntu`인 경우 다음을 실행한다.
다른 전용 group을 사용한다면 `ARTIFACT_UPLOAD_GROUP`으로 전달한다.

```sh
cd /opt/gilbom/deploy
sudo sh infra/scripts/prepare-day8-host.sh

sudo install -o root -g root -m 0755 \
  infra/scripts/promote-ci-release-artifacts.sh \
  /usr/local/sbin/gilbom-promote-ci-release-artifacts
```

`sudo visudo -f /etc/sudoers.d/gilbom-ci-artifact-promotion`을 열어 업로드 계정에
승격 명령 하나만 허용한다.

```text
ubuntu ALL=(root) NOPASSWD: /usr/local/sbin/gilbom-promote-ci-release-artifacts
```

sudoers 파일은 root 소유 mode `0440`이어야 한다. 승격 명령은 인자를 40자리
소문자 SHA 하나로 제한하고, source 경로를 그 SHA의 `.incoming` 디렉터리로
내부에서 다시 계산한다.

Jenkins 전용 Ed25519 key를 만들고 public key만 EC2 `ubuntu` 계정의
`authorized_keys`에 추가한다. 운영 host의 SSH public key를 수집한 뒤
`ssh-keygen -lf` 결과를 운영 담당자가 EC2의 실제 fingerprint와 대조한
`known_hosts` 파일을 준비한다. `StrictHostKeyChecking=no`나 `accept-new`는
사용하지 않는다.

## Jenkins Credential

Jenkins UI의 `Manage Jenkins → Credentials`에서 다음 두 Credential을 만든다.

| ID | Kind | 내용 |
|---|---|---|
| `gilbom-artifact-upload-ssh` | SSH Username with private key | username `ubuntu`, Jenkins 전용 private key |
| `gilbom-artifact-known-hosts` | Secret file | fingerprint를 확인한 `known_hosts` |

Jenkins는 file Credential을 `.../<job>@tmp/secretFiles/...` 아래에 배치한다.
CI 전송 스크립트의 절대경로 검증은 이 Jenkins 관리형 `@tmp` 경로를 허용하되,
공백과 shell metacharacter, symlink는 계속 거부한다.

기본 대상은 Jenkins 컨테이너의 `host.docker.internal:22`다.
다른 EC2 사설 주소를 사용해야 할 때만 Job 환경에 `ARTIFACT_HOST`와 선택적으로
`ARTIFACT_SSH_PORT`를 설정한다.

## Pipeline 동작

1. 비싼 Test 전에 `Artifact transfer readiness`가 SSH host key, incoming 쓰기
   권한, root 소유 승격 helper와 passwordless sudo 허용을 읽기 전용으로 확인한다.
2. `Upload release artifacts to incoming`이 로컬 Checksum을 확인한다.
3. SSH host key를 강제 검증하고 아홉 개의 파일만 SCP/SFTP로 전송한다.
4. EC2 incoming 디렉터리에서 Checksum을 다시 확인한다.
5. `Approve release artifact promotion`에서 Jenkins 사용자가 수동 승인한다.
6. `Promote release artifact atomically`가 제한된 root 명령을 실행한다.
7. root 명령이 구조·파일 형식·Checksum·기존 SHA 충돌을 검사하고 원자적으로
   rename한 뒤 전체 write bit가 제거됐는지 확인한다.

전송 도중 중단된 incoming 디렉터리는 자동 덮어쓰지 않는다. 완전하며 Checksum이
같은 경우에만 재사용하고, 그 외에는 운영자가 원인을 확인한 뒤 해당 SHA의
incoming 디렉터리만 명시적으로 정리해야 한다.
