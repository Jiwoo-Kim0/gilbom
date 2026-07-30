# 변경 로그: Command E2E 문서 상태 동기화

- 작성 시각: 2026-07-30 23:18 (Asia/Seoul)
- 작업 유형: 문서
- 상태: 완료

## 작업 요약

이미 구현·검증·커밋된 Command 비정상 Result 및 권한·Robot Scope·CSRF·감사
Browser E2E에 맞춰 Mock Robot 사용 문서와 Story 상태표를 동기화했다. 제품 코드,
권한 설정, CSRF 설정과 API 계약은 변경하지 않았다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/tools/mock-robot/README.md` | 수정 | 명령 유형별 실패·거절·지연 Result 옵션, 처리 완료 `command_id` 재실행 방지, 중복 Result와 비정상 E2E 실행법을 문서화 |
| `web/docs/2026-07-28-story-subtask-status.md` | 수정 | `S15P11D101-377`을 정상·비정상·보안 Browser E2E 완료 상태로 정정하고 실제 Jetson·EC2 재검증만 후속 범위로 유지 |
| `CHANGLOGS/2026-07-30_1759_command-abnormal-roundtrip.md` | 추가 | 비정상 Command 왕복 검증 당시 변경 범위와 검증 증거를 저장소에 반영 |
| `CHANGLOGS/2026-07-30_2318_command-e2e-documentation-sync.md` | 추가 | 이번 문서 동기화의 변경 파일과 검증 결과를 기록 |

## 상세 내용

- Mock Robot README에 `--command-outcome`, `--command-delay-sec`,
  `--duplicate-results-for` 사용 예와 `command-abnormal-e2e.py` 실행법을 추가했다.
- 상태표는 `9108af1`, `086f84a`, `85e9720` 및 각 변경 로그를 근거로 갱신했다.
- 권한·Scope·CSRF·감사는 구현 변경이 아니라 기존 Browser E2E 완료 사실만
  반영했다.

## 검증

- 실행한 명령:
  - `git show --stat --oneline 9108af1`
  - `git show --stat --oneline 086f84a`
  - `git show --stat --oneline 85e9720`
  - `Get-Content CHANGLOGS/2026-07-30_1759_command-abnormal-roundtrip.md`
  - `Get-Content CHANGLOGS/2026-07-30_1927_command-security-browser-e2e.md`
  - `git diff --check -- web/tools/mock-robot/README.md web/docs/2026-07-28-story-subtask-status.md`
- 결과:
  - 비정상 Command E2E 구현·보강 커밋과 보안 Browser E2E 커밋을 확인했다.
  - 최신 보안 E2E 로그의 익명·권한·Scope·CSRF·감사·멱등 검증 결과와 상태표를
    일치시켰다.
  - 문서 대상 whitespace 오류가 없음을 확인했다.
- 미실행 항목과 사유:
  - 이번 변경은 이미 통과한 E2E 결과의 문서 동기화이므로 Browser E2E를 재실행하지
    않았다.

## 영향 및 주의사항

- 실제 Jetson 및 EC2 Broker 재검증은 여전히 후속 통합 범위다.
- 보안 설정이나 권한 판정 로직은 변경하지 않았다.

## 미완료 및 후속 작업

1. 실제 Jetson에서 여섯 Command의 ACK·최종 Result와 중복 실행 방지를 검증한다.
2. EC2 TLS Broker·MQTT ACL·Nginx SSE를 포함한 운영 환경 왕복을 재검증한다.
