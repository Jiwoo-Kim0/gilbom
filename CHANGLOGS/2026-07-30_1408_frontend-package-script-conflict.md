# 변경 로그: Frontend Package Script 충돌 해결

- 작성 시각: 2026-07-30 14:08 (Asia/Seoul)
- 작업 유형: 설정
- 상태: 완료

## 작업 요약

`web/frontend/gilbom/package.json` 병합 충돌을 해결했다. 현재 Branch의 Mission
Chromium E2E 명령과 병합 대상의 Report 단위 테스트 명령이 같은 `scripts` 객체를
수정해 충돌했으며, 서로 배타적인 변경이 아니므로 두 변경을 모두 보존했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/frontend/gilbom/package.json` | 수정 | 병합 Marker를 제거하고 Report 단위 테스트 `test`, Preview, Mission Fixture E2E, Mission Live E2E Script를 모두 유지했다. 기존 `@playwright/test` 개발 의존성도 보존했다. |
| `CHANGLOGS/2026-07-30_1408_frontend-package-script-conflict.md` | 추가 | 충돌 원인, 선택한 병합 결과와 검증 명령을 기록했다. |

## 상세 내용

충돌 양쪽의 변경은 다음과 같았다.

- 현재 Branch:
  - `preview`
  - `test:e2e:mission`
  - `test:e2e:mission:live`
  - `@playwright/test`
- 병합 대상:
  - `test`
  - `preview`

최종 Script는 다음 네 실행 경로를 모두 제공한다.

```text
test
preview
test:e2e:mission
test:e2e:mission:live
```

`package-lock.json`은 이미 Playwright 의존성을 포함하고 있고 새 Package 의존성
변경이 없으므로 수정하지 않았다. `npm ci`로 Package Manifest와 Lockfile의
일치 여부를 검증했다.

## 검증

- 실행한 명령:
  - `npm ci`
  - `npm test`
  - `npm run lint`
  - `npm run build`
  - `npm run test:e2e:mission`
  - `git diff --name-only --diff-filter=U`
  - `git diff --cached --check`
- 결과:
  - Package 설치 `158`개, 취약점 `0`
  - Report·공통 API 단위 테스트 `13 passed`, 실패 `0`
  - ESLint 통과
  - TypeScript·Vite Production Build 통과
  - 시간·지도 Mission Chromium E2E `2 passed`
  - 미해결 충돌 파일 `0`
  - Staged Diff Whitespace 오류 없음
- 미실행 항목과 사유:
  - `test:e2e:mission:live`는 이번 충돌이 Script Key 병합에 한정되고 이전
    `00d2d87`에서 실제 로그인·MFA·Backend·PostgreSQL 검증이 완료되어
    재실행하지 않았다.

## 영향 및 주의사항

- Report 단위 테스트와 Mission Browser E2E를 서로 덮어쓰지 않고 독립적으로
  실행할 수 있다.
- 병합 과정에 이미 Stage되어 있던 Report·Backend·DataRecorder 변경은 이번
  충돌 해결에서 수정하거나 재해석하지 않았다.
- Stage되지 않은 Mission 화면·Mock Robot·문서 사용자 변경도 건드리지 않았다.

## 미완료 및 후속 작업

- Package 충돌은 해결됐으며 병합 Commit을 완료할 수 있는 상태다.
- 실제 로그인 Mission E2E 재검증이 필요하면
  `python web/scripts/mission-time-browser-e2e.py`를 실행한다.
