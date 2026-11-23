# 변경 로그: 이미지 Provisioning Diff 검사 정정

- 작성 시각: 2026-08-03 17:21 (Asia/Seoul)
- 작업 유형: 수정
- 상태: 완료

## 작업 요약

로봇 이미지 업로드 Provisioning 변경을 커밋하기 전 Staging 영역까지 포함해
`git diff --cached --check`를 실행한 결과, 새 파일 4개에서 EOF 불필요 빈 줄이
발견되어 제거했다. 기존 통합 Provisioning 로그의 `git diff --check` 통과 기록은
당시 미추적 새 파일을 검사하지 못한 결과였음을 정정한다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/backend/src/main/java/com/gilbom/storage/auth/IssuedRobotImageApiKey.java` | 수정 | Record 선언 뒤 EOF 불필요 빈 줄을 제거해 Git 공백 검사를 통과하도록 정리했다. |
| `web/backend/src/main/java/com/gilbom/storage/auth/RobotImageApiKeyIssuanceService.java` | 수정 | 이미지 API Key 회전 Service의 EOF 불필요 빈 줄을 제거했다. |
| `web/backend/src/main/java/com/gilbom/storage/auth/RobotImageApiKeySecretGenerator.java` | 수정 | API Key Secret 생성기의 EOF 불필요 빈 줄을 제거했다. |
| `web/backend/src/main/resources/db/migration/V41__allow_robot_image_api_key_issuance.sql` | 수정 | Runtime INSERT 권한 Migration의 EOF 불필요 빈 줄을 제거했다. |
| `CHANGLOGS/2026-08-03_1721_image-provisioning-diff-check-correction.md` | 추가 | Staged 검사 실패 원인, 정정 파일과 검증 범위를 기록했다. |

## 상세 내용

- 기능, API 계약, DB 권한 내용은 변경하지 않고 파일 끝의 여분 빈 줄만 제거했다.
- `CHANGLOGS/2026-08-03_1645_integrated-robot-provisioning.md`에 기록된 정적 검사는
  미추적 파일을 포함하지 못했으므로 이번 로그의 Staged 검사 결과로 보완한다.

## 검증

- 실행한 명령:
  - 전체 작업 트리 `git diff --check`
  - 관련 파일 Staging 후 `git diff --cached --check`
- 결과:
  - EOF 불필요 빈 줄 4건을 제거한 뒤 공백 오류가 남지 않았다.
- 미실행 항목과 사유:
  - 동작 변경이 없는 EOF 포맷 정정이므로 Backend·Frontend·Embedded 테스트는 다시 실행하지 않았다.

## 영향 및 주의사항

- Runtime 동작과 V41 SQL 권한 내용에는 영향이 없다.
- 새 파일을 포함한 최종 공백 검사는 Working Tree가 아니라 Staging 영역을 기준으로 수행해야 한다.

## 미완료 및 후속 작업

- 없음.
