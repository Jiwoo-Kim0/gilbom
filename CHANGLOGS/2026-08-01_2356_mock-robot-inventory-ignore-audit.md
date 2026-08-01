# 변경 로그: Mock Robot 목록과 Git Ignore 감사

- 작성 시각: 2026-08-01 23:56 (Asia/Seoul)
- 작업 유형: 문서
- 상태: 완료

## 작업 요약

Jira `S15P11D101-680` Mock Robot을 상대 웹 팀과 임베디드 팀이 저장소에서 빠짐없이
찾을 수 있도록 본체·단위 테스트·단발 발행기·전체 E2E·비정상 Command E2E를 README에
목록화했다. 핵심 파일의 Git 추적 상태와 `.gitignore` 적용 여부를 실제 명령으로 감사하고,
Mock 전용 확장 구독과 Jetson MVP 필수 계약의 차이를 명시했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/tools/mock-robot/README.md` | 수정 | Test Bot 구성 파일을 본체·회귀·설명·의존성·단발 점검·Mission E2E·Command E2E·Browser 보조 도구로 분류했다. 빈 과거 Placeholder와 `__pycache__` Ignore 범위, 전용 Credential과 동일 `mqtt_id` 동시 접속 금지를 명시했다. Mock의 `ingest/receipt` 구독은 시험 확장이며 Jetson MVP 필수 하향 Topic은 `commands/request` 하나임을 구분했다. |
| `CHANGLOGS/2026-08-01_2356_mock-robot-inventory-ignore-audit.md` | 추가 | 구성 파일 목록, Git 추적·Ignore 감사, 통신 계약 경계와 검증 결과를 기록했다. |

## 상세 내용

- 현재 통합 검증에서 사용하는 본체는 `web/tools/mock-robot/mock_robot.py`다.
- `publish_test.py`는 Telemetry 한 건만 보내는 연결 점검용이며 전체 Mission 상태 머신을
  대신하지 않는다.
- `mission-mock-e2e.py`는 Mission 정상 흐름, `command-abnormal-e2e.py`는 실패·거절·지연·
  중복 Result 흐름을 검증한다.
- `publisher.py`와 Mock `Dockerfile`은 Git에는 존재하지만 내용이 없는 과거 Placeholder라
  상대 팀의 실행·계약 대조 대상에서 제외했다.
- Mock은 서버 시험을 위해 `ingest/receipt`를 추가 구독한다. 동결된 Jetson MVP 필수
  하향 Topic은 `commands/request`이므로 실기 구현에 Receipt 구독을 강제하지 않는다.
- 핵심 Mock 소스·README·요구사항·단위 테스트와 두 E2E 스크립트는 모두 Git 추적 대상이고
  `.gitignore` 규칙에 걸리지 않는다. Python이 생성하는 `__pycache__/`만 Root
  `.gitignore`의 첫 번째 규칙으로 제외된다.

## 검증

- 실행한 명령:
  - `rg --files web/tools/mock-robot web/scripts | rg "mock|mqtt|command-abnormal|mission"`
  - `git ls-files -- web/tools/mock-robot web/scripts/mission-mock-e2e.py web/scripts/command-abnormal-e2e.py`
  - `git check-ignore -v -- <Mock 핵심 파일과 E2E 파일>`
  - `git check-ignore -v -- web/tools/mock-robot/__pycache__`
  - `git diff --check -- web/tools/mock-robot/README.md`
- 결과:
  - 핵심 Mock 파일과 E2E 파일이 기존 Git 추적 목록에 존재함
  - 핵심 파일 6종 모두 `NOT_IGNORED`
  - `web/tools/mock-robot/__pycache__/`만 `.gitignore:1` 규칙으로 제외됨
  - README whitespace 오류 없음
  - 직전 `2026-08-01_2354` 검증에서 Mock Robot 단위 테스트 7건과 Python 구문 검사 통과
- 미실행 항목과 사유:
  - 이번 변경은 README 목록·계약 경계 문서 보강뿐이라 Broker·DB·Browser E2E를 다시
    실행하지 않았다. Mock Python 코드는 직전 로그 이후 변경하지 않았다.

## 영향 및 주의사항

- 원격 Push 후 상대 팀은 별도 파일 전달 없이 저장소 경로만으로 Mock 구현과 테스트를
  확인할 수 있다.
- Credential·Password·CA 원문, 로컬 Runtime PID·로그와 `__pycache__`는 커밋 대상이 아니다.
- 실제 Jetson과 같은 `mqtt_id`로 Mock을 동시에 실행하면 Broker의 Client ID 충돌과 데이터
  원천 혼합이 발생하므로 전용 Test Robot Credential을 사용한다.
- Mock의 주기 Incident는 테스트 DB에 결함을 누적하므로 운영 Broker에서 사용하지 않는다.

## 미완료 및 후속 작업

1. Jira `S15P11D101-680 #done` Smart Commit으로 Mock 본체·테스트·README와 Mock 전용
   변경 로그를 커밋한다.
2. 원격 Push 후 상대 팀이 README의 파일 목록과 네트워크 없는 7개 단위 테스트를 확인한다.
3. 실제 Jetson 전환 시 Mock을 종료하고 새 `boot_id`의 ONLINE·State·Telemetry와 Command
   Result 왕복을 검증한다.
