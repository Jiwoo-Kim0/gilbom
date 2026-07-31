# Day 7 완료 판정과 담당 범위 기능 동결

기준일: 2026-07-31

작업 시작 Git revision: `2905bb3f6d46`

완료 판정 범위: `web/` 운영 image, Compose, Secret, health, smoke, 복원력,
Runbook

기능 동결 범위: 이 문서 작성자의 완료된 보고서·LLM 및 Day 7 운영 패키징
담당분

비동결 범위: 로봇, 결함 및 다른 담당자의 업무 기능

## 1. 최종 완료 판정

| 게이트 | 판정 근거 | 결과 |
|---|---|---|
| 운영 Compose 단독 기동 | `scripts/verify-production-runtime.sh` | PASS |
| 보고서 container smoke | Template fallback, 승인 PDF hash, 재기동 조회 | PASS |
| 장애·자동 재시작 | Backend/Frontend restart count, DB pool, MQTT reconnect | PASS |
| Non-root | Backend UID 1883, Frontend UID 101, Mosquitto UID 1883 | PASS |
| image Secret 검사 | history, inspect, `docker save`, bundle, runtime log | PASS |
| 실제 readiness | DB, Flyway, PDF·이미지·credential 쓰기 경로 | PASS |
| Backend 전체 테스트 | 760 tests, failures 0, skipped 0 | PASS |
| Frontend test/build | 11 test files, failures 0, skipped 0, lint/build | PASS |
| DB·volume backup | custom dump 검증, PDF·이미지·MQTT archive/hash | PASS |
| 운영 Runbook | Secret, 배포, health, log, backup/restore, rollback | PASS |

위 게이트가 모두 통과했으므로 Day 7을 완료로 판정한다.

최종 local acceptance image:

- Backend `gilbom-backend:local`:
  `sha256:d46ec363c5f5a3851887d742507d4a7550b70b4e0ae800e0a3c64d9d2e35da89`
  (`USER spring`)
- Frontend `gilbom-frontend:local`:
  `sha256:ae066cd77ec30b5e53fbe1c0a222c90a5bd8ec4bc4def4ff9f4936deb20e4dde`
  (`USER nginx`)

local image에는 registry RepoDigest가 없다. 운영 배포는 이 작업을 commit한 Git
revision으로 CI에서 다시 생성·push한 뒤 `repository@sha256:...`를
`.env.prod`와 `deploy.sh` release record에 남겨야 한다.

## 2. 담당 범위 기능 동결 선언

2026-07-31 최종 게이트가 모두 PASS한 시점부터 이 문서 작성자의 완료된
보고서·LLM 업무와 Day 7 운영 패키징 담당분만 기능 동결한다. 이 선언은
프로젝트 전체 기능 동결이 아니다.

동결 대상에서 계속 허용하는 범위:

- 검증으로 재현된 버그 수정과 회귀 테스트
- 취약점·Secret·권한·감사·개인정보 보호 개선
- 성능 병목과 resource 상한 조정
- Docker image, Compose, CI/CD, health, log, backup·복구, rollback 안정화
- 운영 문서와 관측성 보정

이 담당 범위에서 동결하는 기능 확장:

- 보고서의 새 업무 흐름·도메인 상태·상태 전이 추가
- 보고서 API·화면·PDF 계약의 기능 확장
- LLM prompt, response schema, 모델 허용 목록의 무승인 기능 변경
- 보고서 권한 matrix의 기능 확장

로봇·결함 영역은 계속 개발한다. 그 과정에서 필요한 API, 화면, Flyway
migration과 MQTT 변경은 이 동결 선언의 금지 대상이 아니며 각 영역의 담당자와
계약 문서가 정한 검증·승인 절차를 따른다. 다만 공용 인증, 운영 Compose,
readiness 또는 배포 image에 영향을 주면 관련 Day 7 운영 게이트도 재실행한다.

동결된 담당 범위에 기능 예외가 필요하면 변경 전 다음을 남긴다.

1. 장애와 보안 영향, 변경하지 않을 때의 위험
2. API·DB·Frontend·MQTT·LLM 호환성 영향
3. rollback 또는 상태 복구 가능 여부
4. 승인자와 예외 만료 시각
5. Backend/Frontend 전체 회귀, image·Compose gate, container smoke와
   관련 장애 시나리오 재실행 결과

## 3. 변경 관리 기준

- release는 Git revision과 Backend/Frontend registry digest에 1:1로 연결한다.
- tag 이동만으로 재배포하지 않는다.
- migration 적용 뒤 이전 Backend 호환성을 확인하지 않은 자동 rollback을 금지한다.
- 배포 전 backup과 이전 digest가 없으면 change window를 시작하지 않는다.
- Secret 또는 backup 원문을 Git/Jenkins artifact/release record에 넣지 않는다.
- 동결된 담당 범위나 공용 runtime의 dependency 갱신은 전체 gate를 통과해야 한다.

## 4. 장애 검증 요약

- Backend와 Frontend PID 종료 후 자동 재시작·health 복구
- PostgreSQL 재시작 중 readiness 실패, 복구 뒤 Hikari pool 정상화
- MQTT 재시작 중 Backend 유지, 별도 MQTT health와 재연결 복구
- LLM egress 차단 중 일반 API와 Backend health 유지, Template fallback
- LLM Secret 없는 비활성 기동 성공
- 잘못된 DB Secret의 readiness 차단과 원문 비노출
- 전체 Compose 재생성 후 DB 보고서, 승인 PDF와 이미지 SHA-256 유지

세부 증거는 `day7-resilience-verification.md`를 정본으로 사용한다.

## 5. 알려진 제한사항

운영 제한사항과 대응 절차는 `production-deployment-runbook.md` 13장을 따른다.
특히 단일 host Compose, 외부 LLM egress 통제, 외부 TLS termination, named
volume 외부 backup, 실제 GMS canary 승인, 잔여 CVE와 Frontend chunk 기준선을
인수인계 항목으로 유지한다.

## 6. 최종 실행 기록

| 실행 | 결과 |
|---|---|
| `sh scripts/backend-test.sh` | `760 tests, failures=0, skipped=0`, BUILD SUCCESSFUL |
| `npm test && npm run lint && npm run build` | 11/0/0, lint PASS, 2,500 modules build PASS |
| `sh scripts/verify-production-runtime.sh` | 단독 Compose·backup·health·graceful shutdown PASS |
| `IMAGE_SCANNER_MODE=auto sh scripts/verify-production-images.sh` | full mode PASS, Critical fixable 0 |
| `REPORT_SMOKE_SKIP_BUILD=true sh scripts/container-resilience-smoke.sh` | report와 resilience PASS |
| Secret generator 임시 PKI acceptance | 13개 file, 값 비출력, TLS/CA 검증 PASS |

실제 GMS canary는 운영 key와 승인 opt-in이 없어 의도대로 SKIPPED했다.
