# 팀원 B 구현 내용 기록

팀원 B의 작업 기록을 모아두는 폴더다. 완료한 하위 작업은 이
폴더에 독립 문서로 남긴다.

## 목록

| 문서 | 기간 | 범위 | 상태 |
| --- | --- | --- | --- |
| [MQTT-v2-계약과-통신보안_구현내용.md](MQTT-v2-계약과-통신보안_구현내용.md) | 2026-07-27 ~ 07-30 | MQTT v2 정본, AsyncAPI·Schema·Fixture, TLS·ACL, TTL·Replay, Credential 수명주기 | 완료(로컬), 실기 검증 대기 |
| [로봇-상태수집과-실시간-관제_구현내용.md](로봇-상태수집과-실시간-관제_구현내용.md) | 2026-07-27 ~ 07-30 | Telemetry·Presence·State 저장, ONLINE/OFFLINE, Robot REST·SSE·React 관제 | 완료(로컬), 운영 지도 확인 대기 |
| [로봇-명령-왕복과-ACK_구현내용.md](로봇-명령-왕복과-ACK_구현내용.md) | 2026-07-28 ~ 07-30 | Command REST·DB·MQTT 발행, ACK·Result·Timeout·멱등성 | Backend 완료, Frontend·전체 E2E 미완 |
| [순찰구역과-시간기반-Mission_구현내용.md](순찰구역과-시간기반-Mission_구현내용.md) | 2026-07-28 ~ 07-30 | Patrol Area·Route, 시간 기반 Mission, 진행률·ETA, 지도·Browser E2E | 기능 완료, 전체 Mock E2E 미완 |

상위 계획과 현재 우선순위는
[팀원B-remaining-7days.md](../팀원B-remaining-7days.md)를 기준으로 한다.

## 새 하위 작업을 끝냈을 때 작성 순서

1. 실제 코드·설정·테스트 결과를 확인한다.
2. `작업내용/<대표-구현-흐름>_구현내용.md`를 새로 만든다.
3. 이 README 목록에 문서·기간·범위·상태를 추가한다.
4. 상위 계획서의 해당 체크박스와 “오늘 기준 상태”를 갱신한다.
5. 저장소 규칙에 맞는 새 `CHANGLOGS/` 파일을 작성하고 다시 읽는다.
6. 커밋 SHA가 생기면 작업 문서의 커밋 표에 추가한다.

## 작업 청크를 나누는 기준

파일 위치나 Jira 이름이 아니라 **하나의 입력이 같은 정본과 수용 기준을 거쳐
끝까지 흘러가는가**를 기준으로 묶는다.

| 청크 | 함께 묶는 이유 | 별도 분리하는 경계 |
| --- | --- | --- |
| MQTT 계약·TLS·ACL·Credential | Robot이 Broker에 안전하게 연결하고 허용 Topic을 송수신하는 하나의 연결 흐름 | 업무 Payload를 DB에 저장하는 Telemetry·Command Service |
| Telemetry·Presence·State·REST·SSE·관제 UI | Robot 상태 수신→DB 정본→SSE 알림→화면 복구가 하나의 상태 관제 흐름 | 관리자 명령 실행과 Mission 생명주기 |
| Command·ACK·Timeout·제어 UI | 관리자 요청→MQTT 발행→Robot Result→상태 화면이 하나의 왕복 흐름 | Patrol Area·Mission 생성과 진행률 |
| Patrol Area·Mission·진행률·지도 | Area 선택→Mission→Telemetry 진행률·Trajectory→완료가 하나의 임무 흐름 | Broker·Credential 공통 기반 |
| 전체 Mock E2E | 위 청크가 개별 완료된 뒤 경계를 연결해 검증하는 마지막 통합 흐름 | 구현 자체를 E2E 문서에 섞지 않음 |

다음은 묶지 않는다.

- 단순히 같은 `robot/` Package에 있다는 이유
- 같은 날짜나 같은 담당자라는 이유
- 독립적으로 배포·Rollback·검증 가능한 기능
- 다른 팀 소유 기능을 B 완료율에 포함하는 방식

## 작업 문서 필수 구성

새 문서는 다음 순서를 유지한다.

1. **3분 요약**
2. **요구사항과 수용 기준**
3. **무엇을 만들었나**
4. **설계 판단과 그 이유**
5. **검증과 재현 명령**
6. **작업 중 겪은 함정**
7. **미완 항목을 소유자별로 분리**
8. **관련 문서·커밋**

단순히 “구현 완료”라고 쓰지 않는다. 최소한 다음을 함께 기록한다.

- REST·MQTT Topic·SSE Event 또는 화면 Route
- Permission과 Robot 객체 범위
- DB Migration과 핵심 제약
- 정상·실패·중복·지연·권한 실패 검증
- 실제 실행한 명령과 결과 수치
- 실제 Jetson·EC2처럼 실행하지 못한 항목과 사유

## 상태 표기 규칙

| 상태 | 의미 |
| --- | --- |
| `DONE` | 구현과 자동 검증이 끝났고 미실행 항목이 완료 판정을 막지 않음 |
| `DONE (LOCAL)` | 로컬 코드·Mock·TLS Broker 검증 완료, Jetson·EC2만 외부 대기 |
| `IN PROGRESS` | 코드 일부 또는 Fixture가 있지만 핵심 수용 기준이 남음 |
| `WAITING` | 장비·Credential·운영 설정 또는 다른 팀 답변 없이는 진행 불가 |
| `BLOCKED` | 현재 Branch의 빌드·Migration·병합을 실제로 막는 문제가 있음 |

## 링크 규칙

- 이 폴더는 `web/docs/`에서 두 단계 아래다.
- 형제 작업 문서는 파일명만 사용한다.
- `web/docs/` 정본을 가리킬 때는 `../../`를 사용한다.
- 상위 `web/docs/팀원B/` 문서는 `../`를 사용한다.
- 작성 후 모든 상대 링크의 대상 파일이 실제로 존재하는지 검사한다.
