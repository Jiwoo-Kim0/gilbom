# 변경 로그: 압축 Docker layer 이미지 검증 호환

- 작성 시각: 2026-08-02 00:34 (Asia/Seoul)
- 작업 유형: 수정
- 상태: 부분 완료

## 작업 요약

EC2에서 Commit SHA 이미지 배포 전 정적 검증이 `tar: Archive is compressed. Use -z option`으로 중단된 원인을 수정했다. Docker storage backend에 따라 `docker save` archive 내부 layer blob이 비압축 tar 또는 gzip tar로 저장될 수 있으므로, 두 형식을 모두 열어 기존 `.env`·`secrets/` 경로 검사를 동일하게 수행하도록 했다. 검증 실패는 `compose up` 이전에 발생했으며 운영은 기존 `b361cfb...` 이미지로 복구되어 네 서비스가 Healthy인 것을 확인했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/scripts/verify-production-images.sh` | 수정 | 각 Docker layer blob을 임시 파일로 읽고 비압축 tar 검사를 우선 수행한 뒤, 실패 시 gzip 해제 후 tar 파일 목록을 검사한다. 두 방식 모두 실패하면 layer 식별자를 포함한 명시적 오류로 배포를 차단한다. |
| `web/docs/day7-production-image-verification.md` | 수정 | Docker Engine storage backend에 따른 비압축·gzip layer 형식과 동일 경로 정책 적용을 문서화했다. |
| `CHANGLOGS/2026-08-02_0034_compressed-docker-layer-verification.md` | 추가 | EC2 실패 원인, 수정 범위, 로컬 검증 및 운영 재검증 대기 상태를 기록했다. |

## 상세 내용

- 기존 검증은 `docker save`의 layer member를 표준 출력으로 꺼내 곧바로 `tar -tf -`에 전달해 비압축 tar만 처리했다.
- 수정 후 layer member를 작업 디렉터리 안에 저장하고 먼저 비압축 tar로 목록을 읽는다.
- 비압축 검사가 실패하면 `gzip -dc`로 별도 tar를 만든 뒤 같은 파일 경로 검사를 수행한다.
- 압축 형식 차이를 허용할 뿐 Secret exact-match, image history, `.env`, `secrets/`, non-root USER 검사는 변경하지 않았다.
- 운영 `image.env`는 배포 실패 후 백업본으로 복원했으며 실행 컨테이너도 기존 이미지와 일치한다.

## 검증

- 실행한 명령:
  - Git Bash에서 `sh -n scripts/verify-production-images.sh`
  - Git Bash에서 `sh -n scripts/test-verify-production-images.sh`
  - Git Bash에서 `sh scripts/test-verify-production-images.sh`
  - 대상 파일 `git diff --check`
- 결과:
  - 두 Shell script 구문 검사 통과
  - `Production image verifier self-test: PASS`
  - 합성 Secret metadata 이미지 거부와 `.env` layer 이미지 거부 유지
  - diff check 통과
- 미실행 항목과 사유:
  - EC2에서 새 `632bc6c...` Backend·Frontend 이미지에 대한 정적 검증과 실제 재배포는 수정 Commit push·pull 후 수행해야 하므로 대기한다.
  - Flyway V35는 검증기가 `compose up` 전에 실패해 아직 운영 DB에 적용되지 않았다.

## 영향 및 주의사항

- 이 변경은 이미지 검증기를 우회하거나 검사 항목을 줄이지 않는다.
- gzip 실행 파일이 없거나 layer가 지원되지 않는 형식·손상된 archive이면 배포는 계속 실패하도록 닫힌 상태를 유지한다.
- 수정 Commit을 운영 소스에 반영하기 전 기존 배포 명령을 반복해도 같은 `tar` 오류가 발생한다.
- 새 수정 Commit으로 Backend·Frontend 이미지를 다시 빌드해 이미지 tag와 소스 revision을 일치시켜야 한다.

## 미완료 및 후속 작업

- 수정 Commit을 `master`에 push하고 EC2 `/opt/gilbom/app-git`에서 pull한다.
- 새 Commit SHA로 Backend·Frontend 이미지를 다시 빌드한다.
- `/opt/gilbom/deploy`의 검증 스크립트를 같은 revision으로 반영한 뒤 백업된 운영 환경을 유지하며 `deploy.sh`를 재실행한다.
- 배포 후 V35 적용, 네 서비스 Healthy, HTTPS redirect·health, Mission 삭제·제어를 확인한다.
