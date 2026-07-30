# 변경 로그: 팀원 B 구현 문서 체계화

- 작성 시각: 2026-07-30 14:45 (Asia/Seoul)
- 작업 유형: 문서
- 상태: 완료

## 작업 요약

`web/docs/jiw`의 작업 문서 구조를 참고해 `web/docs/팀원B`에 현행 문서 진입점,
남은 7일 계획, 작업 청크 목록과 네 개의 구현 상세 문서를 추가했다. 문서 제목은
“작업 내용” 같은 일반 명칭 대신 MQTT 계약·실시간 관제·명령 왕복·시간 기반
Mission처럼 실제 구현 범위를 대표하도록 작성했다.

서로 같은 종단 간 흐름을 구성하는 기능만 하나의 청크로 묶고, MQTT 연결·보안,
상태 수집·관제, Command·ACK, Patrol Area·Mission을 별도 문서로 분리했다.
이미지 업로드·조회는 팀원 C 범위이므로 팀원 B 완료율과 구현 청크에서 제외했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/docs/팀원B/README.md` | 추가 | V1·V2·현행 구현 문서·기존 원본 기록의 용도와 문서 정본 우선순위를 안내하는 진입점을 추가 |
| `web/docs/팀원B/팀원B-remaining-7days.md` | 추가 | 완료·진행·미완료 상태, 세부 요구사항, 7일 실행 순서, 팀별 요청사항, 제외 범위와 하위 작업 완료 기록 양식을 정리 |
| `web/docs/팀원B/작업내용/README.md` | 추가 | 네 개 구현 청크의 상태와 묶음 기준, 완료 시 문서·검증·CHANGLOG 갱신 절차를 정의 |
| `web/docs/팀원B/작업내용/MQTT-v2-계약과-통신보안_구현내용.md` | 추가 | MQTT v2 Topic·Payload·AsyncAPI·Schema·Fixture 동결과 TLS·ACL·Credential 구현, 재현 명령과 후속 통합 항목을 정리 |
| `web/docs/팀원B/작업내용/로봇-상태수집과-실시간-관제_구현내용.md` | 추가 | Telemetry·Presence·현재 상태·이력·REST·SSE·관제 UI를 하나의 상태 흐름으로 묶어 구현과 요구사항을 정리 |
| `web/docs/팀원B/작업내용/로봇-명령-왕복과-ACK_구현내용.md` | 추가 | Command 생성·MQTT Publish·ACK·Timeout Backend 구현과 아직 없는 Frontend Type·API·권한·Route·화면·E2E를 구분 |
| `web/docs/팀원B/작업내용/순찰구역과-시간기반-Mission_구현내용.md` | 추가 | Patrol Area·Mission 상태 전이·시간 진행률·궤적·지도·Mock E2E를 정리하고 V31 Image·V32 Mission의 미커밋 상태를 명시 |
| `CHANGLOGS/2026-07-30_1445_team-b-implementation-docs.md` | 추가 | 이번 문서 체계화 작업, 검증 결과와 후속 작업을 기록 |

## 상세 내용

### 문서 구조

- 기존 `web/docs/팀원B/V1`은 보관본, `V2`는 현행 계약 참고 문서로 유지했다.
- 기존 `web/docs/팀원B/작업 내용/07-27-1_작업내용.md`는 삭제하거나 이동하지
  않고 과거 원본 기록으로 안내했다.
- 새 구현 문서는 공백 없는 `작업내용/` 아래에 두어 이후 하위 작업이 끝날 때
  같은 규칙으로 갱신할 수 있게 했다.

### 청크 구성

1. MQTT v2 계약·TLS·ACL·Credential은 연결과 접근 통제가 함께 성립해야 하는
   통신 보안 흐름이므로 하나로 묶었다.
2. Telemetry·Presence·State·REST·SSE·관제 UI는 한 상태가 수신부터 화면까지
   전달되는 흐름이므로 하나로 묶었다.
3. Command·MQTT Publish·ACK·Timeout·UI는 명령 왕복 생명주기이므로 하나로
   묶었다.
4. Patrol Area·Mission 상태·진행률·궤적·지도는 순찰 실행 생명주기이므로
   하나로 묶었다.
5. 이미지 Upload·S3·Presigned URL은 팀원 C 책임이어서 별도 범위로 분리했다.

### 최신 상태 반영

- MQTT v2 AsyncAPI·Schema·Fixture 계약은 테스트 기준 동결 상태로 기록했다.
- Mission Mock E2E는 기존 `.last-run.json`의 `passed` 결과에 따라
  `DONE (LOCAL)`로 기록하되, 관련 변경 파일의 정리·커밋은 남았다고 구분했다.
- 현재 Migration 파일은 Image V31·Mission V32이고 버전 중복은 없지만,
  Mission V32는 삭제·신규 파일이 미커밋 상태여서 빈 DB 재검증과 공식 반영이
  필요하다고 기록했다.
- `web/frontend/gilbom/src/features/command`가 존재하지 않으므로 Command
  Frontend와 Broker/Browser E2E를 남은 P0로 기록했다.

## 검증

- 실행한 명령:
  - `python -m unittest discover -s test/mqtt_contract -p 'test_*.py' -v`
  - PowerShell로 Flyway `V*.sql` 버전 그룹 중복 검사 및 V30대 파일 확인
  - PowerShell로 `web/frontend/gilbom/src/features/command` 존재 여부 확인
  - PowerShell로 Mission `.last-run.json` 상태 확인
  - PowerShell 정규식으로 `web/docs/팀원B` Markdown 상대 링크 대상 확인
  - `rg`로 과거 V31 충돌·MQTT 테스트 수·Mission E2E 상태의 오래된 표현 검색
- 결과:
  - MQTT v2 계약 테스트 9개 전체 통과
  - `NO_DUPLICATE_FLYWAY_VERSIONS`
  - `V31__create_robot_image_api_key.sql`,
    `V32__add_time_based_mission_contract.sql` 확인
  - `COMMAND_FRONTEND_DIRECTORY_MISSING` 확인
  - Mission 마지막 실행 상태 `passed` 확인
  - 문서 상대 링크 모두 존재
  - 오래된 상태 표현 없음
- 미실행 항목과 사유:
  - Backend 전체·빈 DB Testcontainers는 이번 작업이 문서 체계화이고 Mission
    V31→V32 이동이 다른 작업의 미커밋 변경인 상태라 실행하지 않았다.
  - Mission Mock E2E는 이번 작업에서 재실행하지 않고 기존 결과 파일과 최신
    구현 로그를 대조했다. V32 정본 반영 후 별도 재실행이 필요하다.
  - 실제 Jetson·EC2 연동은 외부 장비와 배포 환경이 필요해 실행하지 않았다.

## 영향 및 주의사항

- 소스 코드, 설정, DB Migration과 기존 V1·V2 문서는 변경하지 않았다.
- 문서의 `DONE (LOCAL)`은 로컬 검증 완료를 뜻하며 커밋·배포·실장비 통합
  완료를 뜻하지 않는다.
- Flyway 상태는 현재 작업 트리 관찰 결과다. V32 파일이 커밋되고 빈 DB
  검증되기 전까지 과거 Backend 성공 결과를 V32 정본의 성공 근거로 사용하면
  안 된다.
- 향후 구현 문서는 같은 종단 간 흐름을 유지해야 하는 경우에만 기존 청크에
  추가하고, 독립적인 책임이나 검증 기준이면 새 청크로 분리한다.

## 미완료 및 후속 작업

1. Mission V32를 빈 DB·Backend 회귀 테스트로 검증하고 관련 Migration·Mock
   Robot·E2E Harness·Test 파일을 하나의 Mission 흐름 단위로 정리한다.
2. Command Frontend Type·API Client·권한·Route·제어 화면을 구현한다.
3. Command REST→Broker→Mock Robot→ACK→DB/SSE→Browser E2E를 완료한다.
4. 실제 Jetson·EC2에서 MQTT v2, Telemetry, Command, Mission을 검증한다.
5. 각 하위 작업 완료 시 해당 구현 문서의 상태·변경 파일·검증·미완료 항목을
   갱신하고 새 `CHANGLOGS` 파일을 작성한다.
