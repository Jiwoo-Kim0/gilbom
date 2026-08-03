# 변경 로그: CI 이미지 업로드 URL Compose 검증 복구

- 작성 시각: 2026-08-03 18:04 (Asia/Seoul)
- 작업 유형: 수정
- 상태: 완료

## 작업 요약

운영 Compose에서 `GILBOM_STORAGE_UPLOAD_URL`을 필수로 만든 뒤 운영 환경
템플릿에 값을 추가하지 않아 Jenkins `Production Compose contract` 단계가
렌더링 중 실패했다. 운영 템플릿에 Jetson이 접근할 공개 HTTPS 이미지
업로드 URL을 추가하고, Compose 계약 검증에 HTTPS와 localhost 금지 확인을
추가했다. 운영 Compose의 필수값 제약은 유지했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/.env.prod.example` | 수정 | 운영 Backend가 Jetson에 반환할 `GILBOM_STORAGE_UPLOAD_URL=https://i15d101.p.ssafy.io/api/v1/images`를 추가해 Jenkins와 운영 환경 템플릿의 필수값을 동기화 |
| `web/scripts/verify-production-compose.sh` | 수정 | 정규화된 Backend Compose에 공개 업로드 URL이 HTTPS로 주입되고 localhost를 가리키지 않는지 회귀 검증 추가 |
| `web/docs/day7-production-compose.md` | 수정 | 운영 Compose 자동 검증 범위에 Jetson 외부 이미지 업로드 URL의 HTTPS 사용과 localhost 금지를 명시 |
| `CHANGLOGS/2026-08-03_1804_ci-upload-url-compose-fixture.md` | 추가 | Jenkins 실패 원인, 수정 내용, 로컬 검증 결과와 제약을 기록 |

## 상세 내용

- Jenkins의 첫 `docker compose config --quiet`는 로컬 `compose.yaml` 기본값으로
  통과했지만, `verify-production-compose.sh`가 `.env.prod.example`로
  `compose.prod.yaml`을 렌더링하면서 새 필수 변수가 없어 실패했다.
- `compose.prod.yaml`의 `${GILBOM_STORAGE_UPLOAD_URL:?...}` 제약을 완화하지 않고,
  추적 가능한 공개 운영 환경 템플릿에 값을 추가했다.
- 검증 스크립트는 URL 필드 존재만 보지 않고 HTTPS scheme과 localhost 오설정을
  같이 검사한다.

## 검증

- 실행한 명령:
  - PowerShell에서 `.env.prod.example`과 `compose.prod.yaml`의 기본·certificate
    profile `docker compose config --quiet`
  - PowerShell에서 `.env.prod.example`과 `compose.build.yaml`의
    `docker compose config --quiet`
  - 렌더링된 Backend 환경의 `GILBOM_STORAGE_UPLOAD_URL` HTTPS·localhost 검사
  - Linux Alpine 기반 Container에서 `sh -n scripts/verify-production-compose.sh`
  - `git diff --check -- web/.env.prod.example web/scripts/verify-production-compose.sh web/docs/day7-production-compose.md`
- 결과:
  - 운영 Compose 기본·certificate profile와 CI build Compose 렌더링 성공.
  - Backend 렌더링 값은
    `GILBOM_STORAGE_UPLOAD_URL: https://i15d101.p.ssafy.io/api/v1/images`.
  - Shell 문법 검사와 `git diff --check` 통과.
- 미실행 항목과 사유:
  - Windows host에 POSIX `sh`와 설치된 WSL distribution이 없어
    `verify-production-compose.sh`의 전체 직접 실행은 불가능했다. 대신 동일한
    Compose 명령과 신규 assertion을 PowerShell로 재현하고 Linux Container에서 Shell
    문법을 별도 검증했다.
  - Jenkins 전체 Pipeline 재실행은 원격 Jenkins 상태 변경이므로 수행하지
    않았다.

## 영향 및 주의사항

- `.env.prod.example`의 URL은 Secret이 아닌 공개 API 주소다.
- 다른 도메인으로 배포하면 `.env.prod`의 `GILBOM_STORAGE_UPLOAD_URL`을 해당
  Jetson이 접근 가능한 HTTPS 주소로 바꿔야 한다.
- 운영 Compose의 필수값 검증은 계속 유지되므로 실제 `.env.prod`에 누락되면
  배포 전 검증이 의도대로 실패한다.

## 미완료 및 후속 작업

- 변경을 원격 저장소에 반영한 뒤 Jenkins CI를 재실행해
  `Production Compose contract verification: PASS`와 후속 gate를 확인한다.
