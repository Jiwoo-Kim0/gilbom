# Day 8 최종 배포·인수 Runbook

기준일: 2026-08-01

이 문서는 이미 완료된 인증서 발급·갱신, 4GB Swap, backup 무결성, HTTPS/8883
외부 연결 검증에 이어 남은 항목을 닫는 절차다. 새 Naver Maps CSP와 Flyway V34가
포함된 애플리케이션을 아직 EC2에 재배포하지 않았으므로, 아래 게이트가 모두
PASS하기 전에는 Day 8 완료로 선언하지 않는다.

## 1. Git revision과 image 산출물

변경을 GitLab에 commit한 뒤 clean checkout에서 전체 40자리 revision을 얻는다.
Frontend build에는 Naver Maps의 실제 공개 Client ID를 사용한다. Client ID는
브라우저 bundle에 포함되는 공개 식별자이지만 DB·MQTT·LLM·서명 Secret은 어떤
build argument에도 넣지 않는다.

```sh
release_sha="$(git rev-parse HEAD)"
test "$(printf '%s' "$release_sha" | wc -c)" -eq 40
test -z "$(git status --porcelain)"

BACKEND_IMAGE="gilbom-backend:$release_sha" \
FRONTEND_IMAGE="gilbom-frontend:$release_sha" \
VITE_NAVER_MAP_CLIENT_ID='<actual-public-client-id>' \
  docker compose -f compose.build.yaml build backend frontend

JENKINS_IMAGE="gilbom-jenkins:2.555.3-docker-29.6.2" \
  docker compose -f compose.jenkins.yaml build jenkins

sh scripts/verify-production-compose.sh
sh scripts/verify-secret-boundary.sh
sh scripts/verify-day8-infrastructure.sh
sh scripts/verify-supported-versions.sh
sh scripts/verify-frontend-csp-source.sh
sh scripts/verify-frontend-tls.sh "gilbom-frontend:$release_sha"
```

CI에서 Trivy image 검사와 전체 Backend/Frontend 테스트가 성공한 뒤 세 image와
같은 revision의 Git source를 zstd archive로 만든다. 파일명과 image tag의
revision이 같아야 하며 생성 명령이 `SHA256SUMS`를 만든 뒤 즉시 재검증한다.

```sh
BACKEND_IMAGE="gilbom-backend:$release_sha" \
FRONTEND_IMAGE="gilbom-frontend:$release_sha" \
JENKINS_IMAGE="gilbom-jenkins:2.555.3-docker-29.6.2" \
  sh scripts/build-release-artifacts.sh
```

## 2. EC2 운영 경로

현재 `/opt/gilbom/deploy`에 있는 스크립트로 먼저 운영 경로를 확인한다. 이
스크립트는 정해진 경로와 권한만 맞추며 기존 volume이나 Secret을 삭제하지 않는다.
같은 revision의 새 운영 파일 배치는 다음 절에서 수행한다.

```sh
cd /opt/gilbom/deploy
sudo sh infra/scripts/prepare-day8-host.sh

sudo cp --update=none \
  infra/monitoring/monitoring.env.example \
  /opt/gilbom/monitoring/monitoring.env
sudo cp --update=none \
  infra/jenkins/jenkins.env.example \
  /opt/jenkins/jenkins.env
sudo chmod 0600 \
  /opt/gilbom/monitoring/monitoring.env \
  /opt/jenkins/jenkins.env

sudo sh infra/scripts/generate-monitoring-secret.sh
```

기존 파일이 있어 `cp --update=none`이 복사하지 않는 것은 정상이다. 그 경우
새 example과 기존 값을 비교하되 기존 Secret과 운영 값을 덮어쓰지 않는다.

## 3. 재배포 전 backup과 image load

기존 backup을 보존한 채 새 snapshot을 하나 더 만든다.

```sh
cd /opt/gilbom/deploy
sudo env \
  RUNTIME_ENV_FILE="$PWD/runtime.env" \
  IMAGE_ENV_FILE="$PWD/image.env" \
  BACKUP_ROOT=/opt/gilbom/backups \
  sh infra/scripts/backup-volumes.sh
```

출력 bundle의 `robot_images.summary`에는
`database_image_coverage=PASS`가 있어야 한다. DB가 참조하는 AVAILABLE 이미지가
하나라도 없거나 hash가 다르면 운영 backup은 실패하고 기존 서비스가 다시 기동된다.
참조되지 않는 orphan JPEG는 복구 가능성을 위해 archive에 함께 보존한다.

최초 한 번 `infra/scripts/promote-release-artifacts.sh`를 운영 host의
`/usr/local/sbin/gilbom-promote-release-artifacts`에 root 소유 mode `0755`로
설치하고, 배포 계정 sudoers는 이 명령 하나만 비대화형으로 허용한다. 운영 host
key fingerprint를 확인한 `known_hosts` 파일로 SSH 전송·원격 SHA-256 검증·root
승격을 실행한다.

```sh
KNOWN_HOSTS_FILE="$PWD/infra/known_hosts.production" \
  sh scripts/transfer-release-artifacts.sh \
    ".artifacts/release/$release_sha" \
    ubuntu@production-host
```

운영 host에서 root 소유로 승격된 불변 release를 다시 확인하고 해제한다.

```sh
release_dir="/opt/gilbom/artifacts/releases/<40자리 release_sha>"
cd "$release_dir"
sha256sum -c SHA256SUMS

source_archive="gilbom-web-<40자리 release_sha>.tar.zst"
test -f "$source_archive"
archive_runtime_state="$(
  zstd -dc "$source_archive" | tar -tf - \
    | grep -E '(^|/)(runtime\.env|image\.env)$|(^|/)secrets/[^/]+$' \
    | grep -Ev '(^|/)secrets/\.gitignore$' \
    || true
)"
if [ -n "$archive_runtime_state" ]; then
  echo "source archive contains runtime state" >&2
  exit 1
fi

zstd -dc "$source_archive" \
  | sudo tar --extract --file - \
      --directory /opt/gilbom/deploy --no-same-owner

cd /opt/gilbom/deploy
sudo docker compose \
  --env-file runtime.env \
  --env-file image.env \
  -f compose.prod.yaml \
  config --quiet

cd "$release_dir"
for archive in ./*.tar.zst
do
  [ "$archive" != "./$source_archive" ] || continue
  zstd -dc "$archive" | sudo docker image load
done
```

source archive에는 Git 추적 운영 파일만 들어 있으므로 기존 `runtime.env`,
`image.env`, `/opt/gilbom/secrets`, named volume은 보존된다.

`image.env`를 편집기로 열어 두 줄을 한 줄씩 설정한다. 긴 `sed` 명령을 터미널에서
줄바꿈해 실행하지 않는다.

```text
BACKEND_IMAGE=gilbom-backend:<40자리 release_sha>
FRONTEND_IMAGE=gilbom-frontend:<40자리 release_sha>
```

## 4. 애플리케이션 재배포

offline Commit SHA image를 사용할 때 배포 스크립트가 tag와 revision의 일치를
검증한다. Scanner 증거는 CI artifact에 보존하고, EC2에는 scanner가 없으므로
runtime static image 검사를 실행한다.

```sh
cd /opt/gilbom/deploy
sudo env \
  EXPECTED_GIT_REVISION='<40자리 release_sha>' \
  IMAGE_SCANNER_MODE=off \
  SECRET_SCAN_DIR=/opt/gilbom/secrets \
  RUNTIME_ENV_FILE="$PWD/runtime.env" \
  IMAGE_ENV_FILE="$PWD/image.env" \
  COMPOSE_FILE="$PWD/compose.prod.yaml" \
  RELEASE_DIR="$PWD/releases" \
  sh infra/scripts/deploy.sh
```

인증서는 `/opt/gilbom/certbot/nginx`에서 read-only로 다시 mount되므로 재발급하지
않는다. 새 Frontend가 기존 인증서를 읽어 바로 HTTPS mode로 시작해야 한다.

## 5. Monitoring과 Jenkins

관측 stack은 application stack과 분리되어 있고 모든 관리 포트가 기본
`127.0.0.1`에만 열린다. Prometheus는 15일·2GB 중 먼저 도달한 시점까지,
Loki는 14일간 보존한다.

```sh
cd /opt/gilbom/deploy
sudo docker compose \
  --project-name gilbom-monitoring \
  --env-file /opt/gilbom/monitoring/monitoring.env \
  -f compose.monitoring.yaml \
  pull
sudo docker compose \
  --project-name gilbom-monitoring \
  --env-file /opt/gilbom/monitoring/monitoring.env \
  -f compose.monitoring.yaml \
  up --detach --no-build
```

Jenkins custom image archive를 load한 뒤 DinD 고정 image만 pull하고 시작한다.
Jenkins UI는 Executor 1개, Compose build 동시성 1개이며 TLS DinD만 사용한다.

```sh
sudo docker compose \
  --project-name gilbom-jenkins \
  --env-file /opt/jenkins/jenkins.env \
  -f compose.jenkins.yaml \
  pull docker
sudo docker compose \
  --project-name gilbom-jenkins \
  --env-file /opt/jenkins/jenkins.env \
  -f compose.jenkins.yaml \
  up --detach --no-build --wait --wait-timeout 300
```

최초 Jenkins 설정은 운영자 PC에서 SSH tunnel로만 연다.

```powershell
ssh -L 8081:127.0.0.1:8081 ubuntu@i15d101.p.ssafy.io
```

브라우저에서 `http://127.0.0.1:8081`을 열고 화면에 안내된 initial password를
EC2 terminal에서 직접 확인한다. password를 채팅, ticket, Git에 복사하지 않는다.
Grafana도 같은 방식으로 `3001:127.0.0.1:3001` tunnel을 사용한다.

## 6. EC2 자동 게이트

다음 두 명령이 모두 PASS해야 한다. 첫 스크립트는 경로·Swap·image 고정·resource
상한·Secret file·전체 health·PostGIS·Flyway V1~V35·시스템 seed·MQTT·인증서
Timer·실제 HTTPS 보안 헤더·HTTP redirect·오류 은닉·backup 무결성을 확인한다.

```sh
cd /opt/gilbom/deploy
sudo env DOMAIN=i15d101.p.ssafy.io \
  sh scripts/verify-day8-ec2.sh

sudo sh scripts/verify-day8-auxiliary-runtime.sh
```

## 7. 실제 이미지·MQTT·PDF 영속성

운영용으로 승인된 Robot, Mission, Patrol Area와 로봇별 MQTT credential,
이미지 X-API-Key를 사용한다. 개발용 `robot-test`, `MIS-TEST-001`을 운영 Seed로
넣지 않는다. `scripts/issue-robot-image-api-key.py`가 필요한 경우 그 출력은
승인된 credential 발급 변경으로 기록하고 원문 key는 mode `0600` 파일 또는
Secret manager에 한 번만 저장한다.

실제 500KB 이하 JPEG와 credential file을 준비한 뒤 EC2에서 실행한다.

```sh
cd /opt/gilbom/deploy
install -d -m 0700 /home/ubuntu/day8-acceptance

sudo docker run --rm --network host \
  --mount type=bind,src="$PWD/scripts",dst=/work,readonly \
  --mount type=bind,src=/home/ubuntu/day8-acceptance/evidence.jpg,dst=/input/evidence.jpg,readonly \
  --mount type=bind,src=/home/ubuntu/day8-acceptance/robot-mqtt-password,dst=/run/secrets/robot-mqtt-password,readonly \
  --mount type=bind,src=/home/ubuntu/day8-acceptance/robot-image-api-key,dst=/run/secrets/robot-image-api-key,readonly \
  python:3.12-alpine \
  sh -ec '
    apk add --no-cache ca-certificates
    pip install --quiet "paho-mqtt>=2.1,<3"
    exec python /work/publish-dev-incident.py \
      --host i15d101.p.ssafy.io \
      --port 8883 \
      --ca-certificate /etc/ssl/certs/ca-certificates.crt \
      --mqtt-password-file /run/secrets/robot-mqtt-password \
      --mqtt-id "<승인된 mqtt_id>" \
      --robot-id "<승인된 robot UUID>" \
      --api-key-file /run/secrets/robot-image-api-key \
      --mission-id "<승인된 mission_code>" \
      --zone-id "<승인된 area_code>" \
      --lat "<구역 내부 위도>" \
      --lon "<구역 내부 경도>" \
      --image /input/evidence.jpg \
      --image-api https://i15d101.p.ssafy.io/api/v1/images \
      --verify-image-idempotency
  ' | tee /home/ubuntu/day8-acceptance/image-flow.out
```

출력에 `image-idempotency=PASS`와 `day8-result event_id=... image_id=...`가
있어야 한다. 방금 생성된 결함 상세 카드의 `리포트 생성`을 눌러 단건 LLM 보고서를
만든 뒤, 별도 승인 권한 계정으로 검토 요청·승인하여 PDF가 생성된 상태에서 두 UUID를
다음 gate에 전달한다. 이 gate는 DB `local-disk`/object key/hash, MQTT Incident
연결, 실제 파일, 승인 PDF와 Backend 재생성 전후 모든 hash, 서명 Secret의 지속성을
검사한다.

```sh
sudo env \
  EVENT_ID='<출력 event_id>' \
  IMAGE_ID='<출력 image_id>' \
  PERSISTENCE_RECREATE_APPROVED=true \
  sh scripts/verify-day8-persistence.sh
```

마지막 출력의 `defect-id`를 운영자 PC의 서명 URL gate에 사용한다.

## 8. 운영자 PC 서명 URL과 브라우저 검증

password와 TOTP Secret은 명령 인자나 shell history에 넣지 말고 mode `0600`
파일로 준비한다. 스크립트는 정상 JPEG와 `Cache-Control: no-store`, 변조 거부,
실제 5분 만료 후 거부까지 확인한다.

```powershell
$env:GILBOM_BASE_URL = 'https://i15d101.p.ssafy.io'
$env:GILBOM_EMAIL = '<operator email>'
$env:GILBOM_PASSWORD_FILE = 'C:\secure\gilbom-password.txt'
$env:GILBOM_TOTP_SECRET_FILE = 'C:\secure\gilbom-totp-secret.txt'
$env:GILBOM_DEFECT_ID = '<defect-id>'
$env:GILBOM_VERIFY_URL_EXPIRY = 'true'
python .\scripts\verify-day8-signed-image.py
```

브라우저에서는 다음을 직접 확인하고 배포 기록에 결과를 남긴다.

- HTTPS 인증서 경고 없음, 로그인·MFA 성공
- 새 결함 상세 JPEG와 bbox가 표시되고 새로고침 후에도 다시 표시됨
- 새 결함 상세 카드에서 생성한 보고서의 근거가 해당 defect 한 건으로 고정됨
- 승인 보고서 PDF 다운로드 성공
- 결함·로봇 지도 tile, 줌 `+/-`, 우하단 저작권, 마커·방향·팝업·bbox가 정상
- Console에 `nrbe.pstatic.net/styles/...` CSP 차단이 없음
- HTTP URL이 HTTPS로 308 Redirect

새 CSP는 `unsafe-inline`을 허용하지 않는다. 실제 지도 tile이 보이는데 SDK 내부
inline-style 경고만 남고 기능이 정상이라면 허용 범위를 넓히지 않는다. 새
Frontend에서도 지도 전체가 하얗고 Console에 API 인증 오류가 나타날 때만 Naver
Cloud Console의 허용 Web Service URL에
`https://i15d101.p.ssafy.io`가 정확히 등록됐는지 확인한다.

## 9. 완료 판정

다음이 모두 충족될 때만 Day 8을 완료로 표시한다.

- CI 전체 gate와 새 Commit SHA Backend/Frontend image
- `verify-day8-ec2.sh` PASS
- `verify-day8-auxiliary-runtime.sh` PASS
- `verify-day8-persistence.sh` PASS
- `verify-day8-signed-image.py`에서 정상·변조·만료 PASS
- 위 브라우저 7항목 수동 PASS

셋 중 하나라도 실행 전이거나 SKIPPED면 “구현 완료, 운영 인수 미완료” 상태다.
