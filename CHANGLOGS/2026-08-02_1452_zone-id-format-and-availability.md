# 변경 로그: Zone ID 형식 해제와 순찰 구역 코드 중복 확인

- 작성 시각: 2026-08-02 14:52 (Asia/Seoul)
- 작업 유형: 수정
- 상태: 완료

## 작업 요약

순찰 구역 업무 코드를 `A-01` 형식으로 제한하던 Incident DTO, MQTT JSON Schema,
결함 수정 DTO, Embedded 경고와 DB CHECK를 제거했다. `zone_id`는
`robot.patrol_area.area_code`를 해석하지 않고 그대로 전달·보존하며, 한글과 내부
공백을 포함한 `똥방구 설사`도 유효한 코드로 처리한다. 순찰 구역 자체의
`area_code` UNIQUE와 UUID 관계 키는 유지했다.

순찰 구역 등록 전에 UNIQUE 충돌을 확인할 수 있도록
`GET /api/v1/patrol-areas/code-availability`를 추가했다. Frontend는 중복 확인 결과가
false이면 구역 코드 입력칸 아래에 빨간 오류를 표시하고 등록 버튼을 비활성화한다.
조회 직후 발생할 수 있는 동시 등록 경쟁은 기존 POST의
`PATROL_AREA_CODE_CONFLICT`를 같은 필드 오류로 변환해 방어한다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `docs/길봄_DB_DDL.sql` | 수정 | Patrol Area·Defect·Observation의 구역 코드 길이를 50자로 통일하고 `A-01` 정규식 CHECK와 예시를 제거하되 Patrol Area UNIQUE는 유지 |
| `docs/길봄_ERD.sql` | 수정 | Patrol Area·Defect·Observation의 구역 코드 길이와 설명을 불투명 업무 코드 계약으로 수정 |
| `embedded/commands.py` | 수정 | START_MISSION의 zone_id 정규식 경고와 정규식 의존성을 제거하고 전달값을 그대로 상태에 저장 |
| `embedded/comms/contract.py` | 수정 | 더 이상 사용하지 않는 `ZONE_ID_PATTERN` 계약 상수를 제거 |
| `embedded/docs/comms/MQTT-v2-임베디드-대조와-수정_구현내용.md` | 수정 | 기존 형식 검증 기록을 현재 불투명 값 계약이 대체한다는 후속 정정 추가 |
| `embedded/docs/comms/MQTT-송수신-동작-정리.md` | 수정 | Zone ID 형식 강제 설명을 원문 보존 계약으로 수정 |
| `embedded/docs/comms/웹_통신계약_로봇_수신_정리.md` | 수정 | START_MISSION·Incident의 Zone ID를 형식 제한 없는 문자열로 정정 |
| `embedded/scripts/README.md` | 수정 | Embedded 검증 범위에서 Zone ID 패턴 경고를 제거하고 불투명 전달 규칙 명시 |
| `embedded/scripts/mqtt_live_smoke.py` | 수정 | 엄격한 Zone ID 형식 주석을 제거하고 넓은 예제 코드를 기본값으로 사용 |
| `embedded/test/test_commands.py` | 수정 | 형식 경고 테스트를 `똥방구 설사` 원문 보존 테스트로 교체 |
| `MQTT-송수신-동작-정리.md` | 수정 | 현재 MQTT 송수신 설명의 Zone ID 규칙을 불투명 전달로 정정 |
| `웹_통신계약_로봇_수신_정리.md` | 수정 | 웹·로봇 간 Zone ID 형식 강제와 후속 조치 항목을 제거 |
| `test/fixtures/mqtt/v2/valid/command-start-mission.json` | 수정 | 유효 START_MISSION Fixture에 한글·공백 Zone ID 적용 |
| `test/fixtures/mqtt/v2/valid/incident-event.json` | 수정 | 유효 Incident Fixture에 한글·공백 Zone ID 적용 |
| `test/fixtures/mqtt/v2/invalid/incident-retry.json` | 수정 | 재시도 비교 기준 Zone ID를 유효 Fixture와 같은 한글·공백 값으로 동기화 |
| `web/Jenkinsfile` | 수정 | 새 migration에 맞춰 Flyway CI stage, 이력 개수와 결과 키를 V36으로 상향 |
| `web/backend/src/main/java/com/gilbom/defect/DefectDtos.java` | 수정 | 결함 수정 요청 Zone ID의 `A-01` Pattern 검증 제거 |
| `web/backend/src/main/java/com/gilbom/robot/data/mqtt/v2/inbound/IncidentEventPayload.java` | 수정 | Incident 위치 Zone ID의 `A-01` Pattern 검증 제거 |
| `web/backend/src/main/java/com/gilbom/robot/api/PatrolCatalogController.java` | 수정 | MISSION_MANAGE 권한과 no-store 응답을 적용한 구역 코드 가용성 GET API 추가 |
| `web/backend/src/main/java/com/gilbom/robot/data/api/response/PatrolCatalogDtos.java` | 수정 | 정규화된 areaCode와 available을 반환하는 응답 DTO 추가 |
| `web/backend/src/main/java/com/gilbom/robot/mission/PatrolCatalogQueryService.java` | 수정 | 앞뒤 공백을 제거한 코드가 Patrol Area에 존재하는지 UNIQUE 인덱스 경로로 조회 |
| `web/backend/src/main/resources/db/migration/V36__relax_zone_id_format.sql` | 추가 | Defect·Observation·Incident 수집 이슈의 Zone ID CHECK를 내리고 `VARCHAR(50)`으로 확장 |
| `web/backend/src/test/java/com/gilbom/robot/controller/RobotQueryControllerTest.java` | 수정 | 가용성 API 응답·no-store·MISSION_MANAGE 권한·빈 값 검증을 MockMvc로 검증 |
| `web/backend/src/test/java/com/gilbom/robot/integration/MissionManagementIntegrationTest.java` | 수정 | `똥방구 설사` 등록 전 true, 등록 후 false가 되는 실제 PostGIS 가용성 조회 검증 추가 |
| `web/backend/src/test/java/com/gilbom/robot/integration/RobotEventMissionDefectIntegrationTest.java` | 수정 | 한글·공백 Zone ID가 Incident에서 결함·관측·수집 이슈 DB 경로로 전달되도록 Fixture 변경 |
| `web/backend/src/test/java/com/gilbom/robot/mqtt/parser/MqttMessageParserTest.java` | 수정 | START_MISSION parser가 한글·공백 Zone ID를 손실 없이 보존하는지 검증 |
| `web/docs/database-migrations.md` | 수정 | V36 목적·변경 제약·최신 Flyway 범위를 문서화 |
| `web/docs/day8-completion-runbook.md` | 수정 | 운영 검증 Flyway 범위를 V36으로 갱신 |
| `web/docs/defect-schema.md` | 수정 | 결함 Zone ID를 areaCode의 불투명 복사본으로 정의 |
| `web/docs/final-mqtt-topic-schema-v2.md` | 수정 | Incident·START_MISSION Zone ID 형식 제약 제거와 호환성 정정 명시 |
| `web/docs/jenkins-pipeline.md` | 수정 | CI와 build metadata의 Flyway 범위를 V36으로 갱신 |
| `web/docs/jiw/작업내용/결함-목록-지도-상세화면_작업내용.md` | 수정 | 과거 패턴 설명에 V36 이후 형식 제약 해제 정정 추가 |
| `web/docs/mqtt-v2/schemas/command-request.schema.json` | 수정 | START_MISSION parameters.zone_id의 Pattern 제거 |
| `web/docs/mqtt-v2/schemas/common.schema.json` | 수정 | Incident 공통 Zone ID의 Pattern 제거 |
| `web/docs/openapi-v1.yaml` | 수정 | 결함 수정 Zone ID Pattern 제거 및 순찰 구역 코드 가용성 API·응답 Schema 추가 |
| `web/frontend/gilbom/e2e/patrol-area-registration.spec.ts` | 수정 | 중복 코드 빨간 오류·aria-invalid·등록 차단과 사용 가능 코드 등록 흐름을 Browser E2E로 검증 |
| `web/frontend/gilbom/src/features/mission/PatrolAreaManagePage.tsx` | 수정 | 중복 확인 상태·버튼·빨간 필드 오류를 추가하고 POST 409도 동일 오류로 표시 |
| `web/frontend/gilbom/src/features/mission/mission.css` | 수정 | 구역 코드 중복 확인 버튼 높이와 가용성 메시지 정렬 스타일 추가 |
| `web/frontend/gilbom/src/features/mission/missionApi.ts` | 수정 | 구역 코드 가용성 GET API Client 추가 |
| `web/frontend/gilbom/src/features/mission/missionTypes.ts` | 수정 | PatrolAreaCodeAvailability Type 추가 |
| `web/infra/postgres/verify-empty-database.sql` | 수정 | 빈 DB Flyway 성공 이력 개수와 범위를 V36으로 상향 |
| `web/infra/postgres/verify-production-database.sql` | 수정 | 운영 DB Flyway 성공 이력 개수와 범위를 V36으로 상향 |
| `web/scripts/generate-build-metadata.py` | 수정 | 필수 CI 결과 키, 연속 migration 범위와 latestVersion을 V36으로 갱신 |
| `web/scripts/publish-dev-incident.py` | 수정 | 개발 Incident 발행기의 엄격한 Zone ID 패턴 설명 제거 |
| `web/scripts/verify-ci-release-contract.sh` | 수정 | Jenkins Flyway V36 stage를 기대하도록 갱신 |
| `web/scripts/verify-day8-infrastructure.sh` | 수정 | 빈 DB gate의 migration 수를 36개로 검증 |
| `web/scripts/verify-production-runtime.sh` | 수정 | 운영 runtime 검증 주석의 Flyway 범위를 V36으로 갱신 |
| `web/scripts/verify-release-artifacts.sh` | 수정 | release metadata latestVersion을 V36으로 검증 |
| `CHANGLOGS/2026-08-02_1452_zone-id-format-and-availability.md` | 추가 | 이번 변경 범위, 검증 결과, 영향과 후속 작업 기록 |

## 상세 내용

- `zone_id`는 식별 관계의 PK나 FK가 아니다. 실제 Patrol Area PK는 UUID
  `patrol_area_id`이고, 업무 코드 `area_code`에는 UNIQUE가 유지된다.
- Incident와 결함의 `zone_id`는 동일 구역에서 반복되는 것이 정상이며 중복 검사를
  적용하지 않는다.
- V36은 과거 V12·V28이 만든 세 CHECK를 제거한다. 기존 migration은 수정하지 않았다.
- `zone_id` 저장 길이는 원본 `area_code`와 같은 50자다. 문자열 내용의 정규식은 없다.
- 가용성 API는 `areaCode`의 앞뒤 공백만 제거하고 대소문자·한글·내부 공백을
  해석하거나 변환하지 않는다.
- 가용성 조회는 안내용 선검사이며 정합성의 최종 방어선은 DB UNIQUE와 POST 409다.
- Frontend는 중복 확인 전 또는 중복 확인 실패 상태에서 등록 버튼을 비활성화한다.

## 검증

- 실행한 명령:
  - `web/backend/gradlew.bat test --no-daemon --rerun-tasks`
  - 관련 Controller·Mission·Incident·Parser 4개 Class 대상 Gradle Test
  - `python -B -m unittest discover -s test/mqtt_contract -p 'test_*.py' -v`
  - `embedded`에서 `python -m pytest test -q -p no:cacheprovider`
  - `web/frontend/gilbom`에서 `npm test`, `npm run lint`, `npm run build`
  - `npx playwright test e2e/patrol-area-registration.spec.ts`
  - OpenAPI YAML parse, Python compile, Shell syntax 검사
  - `web/scripts/verify-ci-release-contract.sh`
  - `web/scripts/verify-day8-infrastructure.sh`
  - Flyway version 중복·개수 검사와 `git diff --check`
- 결과:
  - Backend 전체 778개: failures 0, skipped 0, BUILD SUCCESSFUL
  - 관련 Backend 63개: failures 0, skipped 0
  - MQTT v2 계약 9개: 모두 통과
  - Embedded 전체 496개: 모두 통과
  - Frontend 단위 38개, ESLint, TypeScript/Vite production build: 모두 통과
  - 순찰 구역 등록 Chromium E2E 1개: 통과
  - CI release contract와 Day 8 infrastructure definition: PASS
  - Flyway V1~V36 36개, 중복 Version 없음
  - OpenAPI YAML parse와 diff whitespace 검사: 통과
  - Backend 전체 테스트 종료 시 Testcontainers DB가 먼저 내려가 Spring Session
    scheduler가 connection 종료 경고를 출력했지만 Test failure는 0이고 Gradle 결과는
    성공이었다.
  - Vite의 기존 500kB 초과 chunk 경고가 남았지만 build는 성공했다.
- 미실행 항목과 사유:
  - 운영 DB에 V36을 실제 적용하거나 배포하지 않았다. 로컬 Testcontainers의 빈 DB에서
    V1~V36 적용과 통합 테스트로 검증했다.
  - 실제 운영 계정·데이터를 사용하는 Browser 검증은 상태 변경을 피하기 위해 수행하지
    않았고, Playwright의 API Mock으로 UI 동작을 검증했다.

## 영향 및 주의사항

- 애플리케이션 배포 전 Flyway V36이 먼저 성공해야 넓은 Zone ID가 DB CHECK에서
  거절되지 않는다.
- `patrol_area.area_code` UNIQUE는 계속 유지되므로 동일 문자열의 구역을 두 번 등록할
  수 없다. Incident·Observation의 반복 Zone ID에는 UNIQUE가 없다.
- 중복 확인 후 등록 전까지 다른 요청이 같은 코드를 선점할 수 있으므로 POST의 409
  처리와 DB UNIQUE를 제거하면 안 된다.
- 외부 Embedded가 기존 `A-01` 값을 계속 보내도 그대로 호환된다.

## 미완료 및 후속 작업

- 운영 반영 시 V36 적용 이력과 `GET /api/v1/patrol-areas/code-availability`의
  MISSION_MANAGE 권한 응답을 확인한다.
- 실제 관리자 Browser에서 중복 코드 빨간 문구와 정상 등록 성공 흐름을 최종 시연한다.
