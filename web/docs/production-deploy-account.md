# Stage 10 EC2 production deploy 계정

Stage 10은 Stage 9에서 승격된
`/opt/gilbom/artifacts/<40자리 release SHA>/`만 운영 Compose에 반영할 수 있는
전용 `deploy` 계정을 만든다. 이 계정은 Docker group, `sudo`·`admin`·`wheel`
group에 가입하지 않으며 비밀번호 인증을 사용할 수 없다.

## 자동화되는 범위

`infra/scripts/prepare-production-deploy-account.sh`가 다음 항목을 멱등하게
설정한다.

- `deploy` 사용자와 전용 primary group 생성
- 모든 supplementary group 제거와 password lock
- Jenkins Ed25519 public key 하나만 `authorized_keys`에 설치
- SSH forwarding·TTY 차단과 public-key-only 인증
- SSH key에 `/usr/local/sbin/gilbom-deploy-ssh-command` forced command 적용
- root 소유 `gilbom-deploy`와 검증 명령 설치
- `/opt/gilbom/deploy` 정본을 root 소유·group/world 쓰기 금지로 고정
- 다음 sudoers 한 줄만 허용

```text
deploy ALL=(root) NOPASSWD: /usr/local/sbin/gilbom-deploy
```

`gilbom-deploy`는 정확히 40자리 소문자 Commit SHA 하나만 받는다. Artifact root,
Compose, runtime environment, image environment와 실제 배포 스크립트 경로는
모두 고정되어 있다. 호출자가 환경변수나 두 번째 인자로 경로를 바꿀 수 없다.

## 1. Jenkins 전용 Key 생성

관리자 PC의 안전한 로컬 터미널에서 Jenkins 전용 Ed25519 key를 만든다. 기존
Artifact upload key나 개인 EC2 key를 재사용하지 않는다.

```sh
ssh-keygen \
  -t ed25519 \
  -a 100 \
  -C gilbom-production-deploy \
  -f gilbom-production-deploy
```

private key `gilbom-production-deploy`는 EC2와 Git에 복사하지 않는다. MobaXterm의
SFTP로 public key `gilbom-production-deploy.pub`만 EC2의 임시 경로로 전송한다.

## 2. EC2 최초 설정

MobaXterm으로 기존 관리자 계정에 접속한 뒤 실행한다.

```sh
cd /opt/gilbom/deploy
sudo apt-get update
sudo apt-get install -y jq zstd

sudo sh infra/scripts/prepare-production-deploy-account.sh \
  /tmp/gilbom-production-deploy.pub

sudo /usr/local/sbin/gilbom-verify-production-deploy-account
```

두 명령이 모두 `PASS`이면 EC2 account boundary가 준비된 것이다. public key는
`/home/deploy/.ssh/authorized_keys`에 설치되었으므로 임시 `.pub` 파일을 제거할
수 있다.

## 3. Jenkins Credential

`Manage Jenkins → Credentials`에서 다음 Credential을 별도로 만든다.

```text
ID: gilbom-production-deploy
Kind: SSH Username with private key
Username: deploy
Private Key: Enter directly
```

Credential을 저장한 뒤 관리자 PC의 private key 원본은 팀의 승인된 Secret
manager에 보관하거나 정책에 따라 안전하게 폐기한다. Jenkins Console, Git,
EC2 filesystem에는 private key 내용을 출력하거나 복사하지 않는다.

Jenkins가 사용할 수 있는 원격 명령 형식은 다음 하나뿐이다.

```sh
ssh deploy@EC2 \
  'sudo --non-interactive /usr/local/sbin/gilbom-deploy <40자리 release SHA>'
```

## 4. 부정 테스트

EC2의 root 검증 명령이 다음을 실제로 확인한다.

- `deploy`의 `sudo docker` 실행 실패
- `deploy`의 일반 root shell 실행 실패
- 잘못된 SHA 입력 실패
- 두 번째 인자 전달 실패
- 환경변수를 이용한 임의 Compose 경로 전달 실패
- Docker·sudo·admin·wheel group 미가입
- password lock, SSH public-key-only와 forced command

```sh
sudo /usr/local/sbin/gilbom-verify-production-deploy-account
```

성공 결과:

```text
Production deploy account verification: PASS
```

## 사용자에게 남는 작업

저장소의 스크립트와 부정 테스트는 CI에서 검증한다. 운영 권한이 필요한 다음 두
작업만 사람이 수행한다.

1. EC2에서 준비 스크립트를 `sudo`로 한 번 실행한다.
2. Jenkins UI에 `gilbom-production-deploy` private-key Credential을 등록한다.

Stage 10은 계정과 권한 경계를 준비하는 단계다. Jenkins production deploy
stage 연결과 실제 SHA 배포 승인은 별도 변경으로 진행한다.
