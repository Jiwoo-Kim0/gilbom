# 변경 로그: Codex Gradle 빌드 산출물 제외

- 작성 시각: 2026-07-30 16:03 (Asia/Seoul)
- 작업 유형: 설정
- 상태: 완료

## 작업 요약

Backend 테스트에서 생성된 `build-codex-*` 격리 Gradle 빌드 디렉터리가 수백 개의
미추적 파일로 표시되지 않도록 저장소 루트 `.gitignore`에 전용 패턴을 추가했다.
기존 빌드 산출물은 삭제하지 않았으며, 기존 `DailyLog/` 제외 규칙도 보존했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `.gitignore` | 수정 | `/web/backend/build-codex-*/` 패턴을 추가해 `build-codex-registry`, `build-codex-registry-full`, `build-codex-registry-full-e2e`와 이후 같은 접두사의 격리 Gradle 빌드 산출물을 Git 추적 대상에서 제외 |
| `CHANGLOGS/2026-07-30_1603_ignore-codex-build-artifacts.md` | 추가 | 제외 대상, 검증 결과, 파일을 삭제하지 않았다는 사실을 기록 |

## 상세 내용

- 저장소 루트 기준으로 Backend 바로 아래의 `build-codex-*` 디렉터리만 일치하도록
  선행 `/`와 후행 `/`를 포함한 패턴을 사용했다.
- 일반 소스 경로나 다른 모듈의 `build` 이름에는 영향을 주지 않는다.
- 실제 디렉터리와 파일은 그대로 유지되므로 필요하면 기존 테스트 보고서를 계속
  열어볼 수 있다.
- 작업 전부터 있던 `.gitignore`의 `DailyLog/` 변경은 되돌리지 않았다.

## 검증

- 실행한 명령:
  - `git check-ignore -v`로 세 빌드 디렉터리의 대표 파일 확인
  - `git ls-files --others --exclude-standard`
  - `git diff --check -- .gitignore`
- 결과:
  - 세 대표 파일 모두 `.gitignore` 12행의
    `/web/backend/build-codex-*/` 규칙과 일치
  - Git 상태에 노출되는 `build-codex-*` 미추적 파일 365개에서 0개로 감소
  - 전체 미추적 파일 수 91개로 감소
  - Whitespace 오류 없음
- 미실행 항목과 사유:
  - 소스 코드나 실행 동작은 변경하지 않아 Backend·Frontend 테스트를 재실행하지
    않았다.

## 영향 및 주의사항

- 이미 Git에 추적된 파일에는 Ignore 규칙이 적용되지 않지만, 해당 빌드 폴더의
  파일은 모두 미추적 산출물이므로 현재 목적에 맞게 동작한다.
- Ignore는 파일을 삭제하지 않는다. 디스크 공간 정리가 필요하면 별도의 명시적
  삭제 작업이 필요하다.
- Robot·Command 프론트 소스, CHANGLOGS, TROUBLESHOOTING 등 나머지 미추적
  파일은 이 규칙의 영향을 받지 않는다.

## 미완료 및 후속 작업

- `.gitignore`와 이 변경 로그의 커밋 여부는 사용자 커밋 범위에 맞춰 결정한다.
- Playwright `test-results/` 등 다른 생성 산출물도 불편하다면 별도 규칙으로
  검토한다.
