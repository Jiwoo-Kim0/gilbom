# Day 10 장애·복구 검증 Runbook

이 Runbook은 운영 복구 자산을 검증하되 운영 DB에 복원하거나 운영 EC2를
재부팅하지 않는 절차다.

## 1. 현재 판정

| 항목 | 상태 | 완료 증거 |
| --- | --- | --- |
| 기존 CI 강제 재시작·Volume 보존·Rollback A/B/C | 자동화됨 | Jenkins `resilience-recovery`, `volume-persistence`, Rollback Drill Artifact |
| 실제 backup의 새 DB·image·PDF Volume 복원 | 구현 완료, 실제 bundle 실행 필요 | `.artifacts/day10/restore/summary.json` |
| 스테이징 EC2 강제 재시작·reboot | `PENDING_NO_STAGING` | `(예정) .artifacts/day10/staging/reboot-recovery.json` |
| 스테이징 인증서 ACME dry-run | `PENDING_NO_STAGING` | `(예정) .artifacts/day10/staging/certbot-dry-run.txt` |
| 스테이징 CI/CD Rollback 재훈련 | `PENDING_NO_STAGING` | `(예정) .artifacts/day10/staging/rollback-rehearsal.json` |

`PENDING`은 PASS가 아니다. 스테이징 EC2와 운영에서 분리된 배포 Job이 생기기
전에는 뒤 세 항목을 완료로 기록하지 않는다.

## 2. 새 형식의 실제 backup 준비

새 backup은 다음 다섯 manifest를 포함해야 한다.

```text
database.content.sha256
robot_images.database.sha256
robot_images.volume.sha256
report_pdf_data.database.sha256
report_pdf_data.volume.sha256
```

기존 bundle에 이 파일이 없다면 새 backup을 생성해야 한다. Volume backup은
일관된 snapshot을 위해 Frontend·Backend·MQTT writer를 잠시 멈췄다가
`docker compose up --no-build --wait`로 복구한다. 따라서 운영에서 수동 실행할
때는 짧은 서비스 중단이 허용된 시간과 담당자 승인이 필요하다. 승인이 없다면
설정된 `gilbom-volume-backup.timer`의 다음 정상 실행을 기다린다.

승인된 경우 운영 EC2에서:

```sh
sudo /usr/local/sbin/gilbom-backup volumes

sudo systemctl status gilbom-volume-backup.service --no-pager
sudo find /opt/gilbom/backups \
  -mindepth 1 -maxdepth 1 -type d \
  -name '*-gilbom' -printf '%T@ %p\n' \
  | sort -n | tail -n 1
```

출력된 bundle은 암호화 archive, `SHA256SUMS`, manifest를 빠짐없이 보안
복원 Runner로 전달한다. GPG Private key와 TOTP keyring은 운영 EC2나 Git에
올리지 않는다.

## 3. 격리 복원 실행

GPG Private key를 보관하는 별도 복원 Runner에서 저장소의 정확한 revision을
checkout하고 다음을 실행한다.

```sh
cd web

export GNUPGHOME=/secure/gilbom-backup-keyring
export TOTP_KEYRING_FILE=/secure/gilbom_mfa_totp_keyring

RESTORE_DRILL_OUTPUT_DIR=.artifacts/day10/restore \
  sh scripts/run-isolated-backup-restore-drill.sh \
  /secure/backups/<timestamp>-gilbom

jq . .artifacts/day10/restore/summary.json
```

완료 조건:

- `status`가 `PASS`
- `bundleChecksum`, `databaseContentHash`가 `true`
- `robotImageVolumeHash`, `robotImageReferenceHash`가 `true`
- `reportPdfVolumeHash`, `reportPdfReferenceHash`가 `true`
- `productionDatabaseRestore`, `productionHostReboot`가 `false`

Drill은 `restore-drill-*` 이름의 internal Network, DB container와 Named
Volume만 사용하고 기본 실행에서는 성공·실패와 관계없이 모두 정리한다.

## 4. 스테이징 후속 순서

스테이징 EC2가 준비된 뒤 다음 순서로 수행한다.

1. 스테이징 전용 `runtime.env`, Secret, Artifact root와 도메인인지 확인한다.
2. Backend → DB → MQTT를 한 번씩 강제 재시작하고 각 단계의 HTTPS health와
   Compose health 회복 시간을 기록한다.
3. 현재 boot ID, Release SHA, Compose 상태를 수집한 뒤 승인된 스테이징
   유지보수 시간에만 EC2를 reboot한다.
4. 재접속 후 boot ID가 바뀌었고 Docker, Compose 서비스, backup·certificate
   timer가 자동 시작됐는지 JSON으로 기록한다.
5. 스테이징 도메인에서
   `sudo /usr/local/sbin/gilbom-certificate dry-run`을 실행하고 ACME 성공
   로그와 전후 HTTPS health를 보존한다.
6. 운영과 다른 스테이징 Artifact root·Job에서 동일 SHA를 배포하고
   Migration 없는 장애의 자동 Rollback과 Migration 변경 시 차단을 재훈련한다.

운영 Job, `/opt/gilbom/artifacts/releases`, 운영 DB, 운영 도메인을 스테이징
훈련에 재사용하지 않는다. 분리 경계를 증명할 수 없으면 실행하지 않고
`PENDING_NO_STAGING` 상태를 유지한다.
