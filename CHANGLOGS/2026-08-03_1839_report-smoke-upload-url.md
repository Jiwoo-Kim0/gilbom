# 변경 로그: Report Smoke 이미지 업로드 URL 계약 복구

- 작성 시각: 2026-08-03 18:39 (Asia/Seoul)
- 작업 유형: 수정
- 상태: 완료

## 작업 요약

Jenkins가 Production Compose, Backend·Frontend build, Flyway V41과 Integration smoke까지
통과한 뒤 `container-resilience-smoke.sh`의 임시 환경 파일에
`GILBOM_STORAGE_UPLOAD_URL`이 없어 Production+Smoke Compose 렌더링이 실패했다.
Report/Resilience Smoke 전용 고정 HTTPS `.invalid` URL을 추가하고, Repository Policy
단계에서 누락을 즉시 탐지하도록 정적 계약을 보강했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/scripts/container-report-smoke.sh` | 수정 | 임시 `.env.smoke`에 `https://report-smoke.example.invalid/api/v1/images`를 추가하고 Smoke DB의 Flyway 성공 이력을 V1~V41 41개로 정확히 검증 |
| `web/scripts/verify-day8-infrastructure.sh` | 수정 | Report Smoke의 업로드 URL fixture와 Flyway 41개 검사가 있는지 Repository Policy에서 정적 검증 |
| `web/docs/day7-container-report-smoke.md` | 수정 | Smoke의 `.invalid` HTTPS URL 용도와 외부 업로드 미사용 경계를 명시하고 Flyway 기준을 41개로 갱신 |
| `CHANGLOGS/2026-08-03_1839_report-smoke-upload-url.md` | 추가 | Jenkins 실패 원인, Smoke fixture 수정과 검증 결과를 기록 |

## 상세 내용

- `container-resilience-smoke.sh`는 `container-report-smoke.sh`를 상위 진입점으로
  사용하므로, 실제 누락 지점은 `write_environment()`가 생성하는
  `.env.smoke`였다.
- Smoke URL은 HTTPS 필수 계약을 유지하면서 실제 외부 호스트로 요청이
  나가지 않는 RFC 예약 `.invalid` 도메인을 사용한다.
- 운영 `compose.prod.yaml`의 필수값 제약은 완화하지 않았다.
- Smoke DB 검증의 기존 `COUNT(*) > 0`을 `COUNT(*) = 41`로 강화해 V41 누락을
  성공으로 판정하지 않도록 했다.

## 검증

- 실행한 명령:
  - Linux Alpine 기반 Container에서 `container-report-smoke.sh`,
    `verify-day8-infrastructure.sh`의 `sh -n`
  - Linux Alpine 기반 Container에서 Upload URL·Flyway 41개·Repository Policy
    assertion 정적 검사
  - PowerShell에서 `compose.prod.yaml` + `compose.smoke.yaml` 렌더링
  - 대상 파일 `git diff --check`
- 결과:
  - Production+Smoke Compose 렌더링 성공.
  - Backend 환경에
    `GILBOM_STORAGE_UPLOAD_URL: https://report-smoke.example.invalid/api/v1/images`
    주입 확인.
  - 변경 Shell 문법·정적 계약·`git diff --check` 통과.
- 미실행 항목과 사유:
  - Report/Resilience Smoke 전체는 Linux Docker CLI와 임시 Stack 기동·장애 주입이
    필요하여 Windows 로컬에서 실행하지 않았다. 실패한 선행 렌더링
    계약은 동일한 Compose 파일 조합으로 재현해 통과했다.
  - Jenkins 전체 Pipeline 재실행은 원격 SCM 반영 후 확인해야 하므로
    실행하지 않았다.

## 영향 및 주의사항

- `.invalid` URL은 Smoke 전용 공개 fixture이며 실제 이미지 업로드 E2E 대상이
  아니다.
- 실제 운영 배포는 계속 `.env.prod`의 Jetson 접근 가능한 HTTPS URL을
  사용한다.
- Smoke 종료의 `down --volumes`는 고유 Compose project의 일회성 Volume만
  삭제하며 운영 Volume에는 사용하지 않는다.

## 미완료 및 후속 작업

- 원격 반영 후 Jenkins에서 `Report LLM fallback and resilience suite`와 후속
  Release·Security gate가 통과하는지 확인한다.
