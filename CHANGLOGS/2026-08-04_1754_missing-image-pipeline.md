# 변경 로그: 미설치 이미지 Incident 파이프라인

- 작성 시각: 2026-08-04 17:54 (Asia/Seoul)
- 작업 유형: 기능
- 상태: 완료

## 작업 요약

점자블록 파손 `DAMAGED`와 미설치 `MISSING`을 동일한 MQTT Incident Topic으로 수신하되 `incident.defect_type`으로 구분하도록 계약과 구현을 확장했다. `MISSING`은 `confidence`와 `bbox_norm` 키를 유지하면서 값은 `null`로 보낼 수 있고, `DAMAGED`는 두 값이 모두 있어야 한다. 미설치 이미지도 기존 HTTPS 선업로드, Docker Volume 원본 저장, PostgreSQL 메타데이터 저장, MQTT `image_id` 연결, 서명 URL 조회 파이프라인을 그대로 사용한다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/Jenkinsfile` | 수정 | Flyway 검증 Stage와 CI 결과 식별자를 V1~V42 기준으로 갱신했다. |
| `web/backend/src/main/java/com/gilbom/defect/DefectFilter.java` | 수정 | 결함 조회 필터 유형에 `MISSING`을 추가했다. |
| `web/backend/src/main/java/com/gilbom/defect/IncidentEventIngestionService.java` | 수정 | nullable 신뢰도와 bbox를 안전하게 저장하고, 신뢰도 없는 미설치 관측을 재검토 대상으로 처리하며 기존 대표 결함의 최신 신뢰도를 보존하게 했다. |
| `web/backend/src/main/java/com/gilbom/report/generation/ReportGenerationDataRepository.java` | 수정 | 보고서용 결함 유형 축에 `MISSING`을 추가했다. |
| `web/backend/src/main/java/com/gilbom/robot/data/mqtt/v2/inbound/IncidentEventPayload.java` | 수정 | `MISSING` Enum과 nullable 신뢰도·bbox를 추가하고 `DAMAGED`에만 두 값을 필수로 강제하는 교차 필드 검증을 추가했다. |
| `web/backend/src/main/java/com/gilbom/statistics/StatisticsDtos.java` | 수정 | 통계 응답 결함 유형에 `MISSING`을 추가했다. |
| `web/backend/src/main/resources/db/migration/V42__accept_missing_incident_images.sql` | 추가 | 결함·관측·수신 문제 유형 제약에 `MISSING`을 추가하고 관측 및 검토 대기 신뢰도 컬럼을 nullable로 확장했다. |
| `web/backend/src/test/java/com/gilbom/report/generation/ReportGenerationDataRepositoryIntegrationTest.java` | 수정 | 보고서 유형 집계의 `MISSING` 0건 포함 계약을 검증하도록 기대값을 갱신했다. |
| `web/backend/src/test/java/com/gilbom/robot/integration/PatrolMissionSchemaIntegrationTest.java` | 수정 | 순찰 통계 유형 축에 `MISSING`이 포함되는지 검증했다. |
| `web/backend/src/test/java/com/gilbom/robot/mqtt/parser/MqttMessageParserTest.java` | 수정 | nullable 필드의 `MISSING`은 수락하고 동일 형태의 `DAMAGED`는 거부하는 파서 검증을 추가했다. |
| `web/backend/src/test/java/com/gilbom/statistics/StatisticsQueryServiceTest.java` | 수정 | 결함 유형별 통계에 `MISSING` 0건이 채워지는지 검증했다. |
| `web/backend/src/test/java/com/gilbom/storage/integration/IncidentImageLinkIntegrationTest.java` | 수정 | 실제 JPEG 업로드 후 `MISSING` Incident가 이미지·관측·대표 결함·보고서 조회까지 연결되는 통합 테스트를 추가했다. |
| `web/docs/cd-automation.md` | 수정 | 운영 배포 DB 확인 기준을 V42로 갱신했다. |
| `web/docs/database-migrations.md` | 수정 | V42 마이그레이션 목적, 제약 및 nullable 변경 내용을 기록했다. |
| `web/docs/day8-completion-runbook.md` | 수정 | Flyway 검증 범위를 V1~V42로 갱신했다. |
| `web/docs/field-integration-open-issues.md` | 수정 | 미설치 계약 항목을 로컬 구현 완료·운영 E2E 대기 상태로 갱신하고 동일 Topic과 명시적 null 규칙을 기록했다. |
| `web/docs/final-mqtt-topic-schema-v2.md` | 수정 | 동일 Incident Topic의 `DAMAGED`·`MISSING` payload, 조건부 필드 및 이미지 연결 계약을 명시했다. |
| `web/docs/jenkins-pipeline.md` | 수정 | Jenkins Flyway 검증 명칭과 범위를 V42로 갱신했다. |
| `web/docs/mqtt-device-db-mapping.md` | 수정 | Robot 발행 유형과 nullable 신뢰도·bbox의 DB 매핑을 갱신했다. |
| `web/docs/mqtt-v2/schemas/incident-event.schema.json` | 수정 | `MISSING`을 허용하고 nullable 필드를 선언하되 `DAMAGED`에는 숫자·배열을 강제하는 조건부 JSON Schema를 추가했다. |
| `web/docs/openapi-v1.yaml` | 수정 | REST API `DefectType` Enum에 `MISSING`을 추가했다. |
| `web/docs/patrol-mission-statistics-fixture.md` | 수정 | 통계 픽스처 기대 유형에 `MISSING` 0건 행을 추가했다. |
| `web/docs/report-llm-result.schema.json` | 수정 | 보고서 LLM 결과의 결함 유형 Enum에 `MISSING`을 추가했다. |
| `web/frontend/gilbom/src/features/defect/defectTypes.ts` | 수정 | 결함 화면 유형과 한글 라벨 `미설치`를 추가했다. |
| `web/frontend/gilbom/src/features/report/reportTypes.ts` | 수정 | 보고서 화면 결함 유형에 `MISSING`을 추가했다. |
| `web/infra/postgres/verify-empty-database.sql` | 수정 | 빈 DB Flyway 버전 연속성 검증을 V1~V42로 갱신했다. |
| `web/infra/postgres/verify-production-database.sql` | 수정 | 운영 DB Flyway 버전 검증을 V1~V42로 갱신했다. |
| `web/scripts/container-report-smoke.sh` | 수정 | 컨테이너 보고서 Smoke의 예상 마이그레이션 수를 42로 갱신했다. |
| `web/scripts/generate-build-metadata.py` | 수정 | Release 메타데이터의 Flyway 증적 식별자와 버전 범위를 V42로 갱신했다. |
| `web/scripts/generate-cicd-notification.py` | 수정 | CI/CD 알림의 Flyway Stage 식별자를 V42로 갱신했다. |
| `web/scripts/test-cicd-notification.py` | 수정 | 알림 테스트 기대 식별자를 V42로 갱신했다. |
| `web/scripts/verify-ci-release-contract.sh` | 수정 | CI Release 계약의 Flyway Stage와 마이그레이션 수를 V42 기준으로 갱신했다. |
| `web/scripts/verify-day8-infrastructure.sh` | 수정 | Day 8 인프라 검증의 Flyway 수와 메시지를 V42로 갱신했다. |
| `web/scripts/verify-production-runtime.sh` | 수정 | 운영 Runtime 검증 주석의 기준 버전을 V42로 갱신했다. |
| `web/scripts/verify-release-artifacts.sh` | 수정 | Release 증적의 Flyway 결과 및 버전 배열 검증을 V42로 갱신했다. |
| `web/scripts/verify-stage15-cicd-operations-contract.sh` | 수정 | Stage 15 CI/CD 운영 계약의 Flyway 식별자를 V42로 갱신했다. |
| `CHANGLOGS/2026-08-04_1754_missing-image-pipeline.md` | 추가 | 구현 범위, 검증 결과, 배포 주의사항과 후속 실차 E2E를 기록했다. |

## 상세 내용

- Topic은 `tbs/v2/robots/{mqtt_id}/events/incident` 하나를 유지한다.
- `incident.defect_type=DAMAGED`이면 `incident.confidence`는 `0.0~1.0` 숫자, `evidence.bbox_norm`은 정규화 좌표 4개 배열이어야 한다.
- `incident.defect_type=MISSING`이면 두 키는 payload에 유지하고 값은 명시적 `null`로 전송할 수 있다.
- 두 유형 모두 이미지 업로드 성공 시 반환된 UUID를 `evidence.image_id`에 넣는다. 이미지 업로드 실패 시에만 `image_id=null`을 사용할 수 있다.
- `MISSING`의 null 신뢰도·bbox는 SQL NULL로 저장하고 해당 결함은 관리자 재검토 대상으로 표시한다.
- 운영 Backend 적용 전 Flyway V42가 실행되어야 DB CHECK와 nullable 제약이 새 계약을 수용한다.

## 검증

- 실행한 명령:
  - `./gradlew test --no-daemon --tests MqttMessageParserTest --tests IncidentImageLinkIntegrationTest --tests StatisticsQueryServiceTest --tests PatrolMissionSchemaIntegrationTest --tests ReportGenerationDataRepositoryIntegrationTest`
  - `./gradlew test --no-daemon`
  - `npm test`
  - `npm run lint`
  - `npm run build`
  - `sh scripts/verify-ci-release-contract.sh`
  - `sh scripts/verify-day8-infrastructure.sh`
  - `python scripts/test-cicd-notification.py`
  - Python 3.11 `json.load`로 Incident 및 보고서 JSON Schema 파싱
  - 변경 대상 기준 `git diff --check`
- 결과:
  - Backend 집중 테스트 58건 성공, 실패 0, Skip 0.
  - Backend 전체 테스트 802건 성공, 실패 0, Skip 0.
  - Frontend 테스트 44건 성공, 실패 0; Lint 및 Production Build 성공. Build에는 기존 500KB 초과 Chunk 경고만 있다.
  - CI Release 계약과 Day 8 인프라 계약 검증 성공.
  - CI/CD 알림 Python 테스트 5건 성공.
  - JSON 파일 2개 파싱 성공, 변경 대상 `git diff --check` 오류 없음.
- 미실행 항목과 사유:
  - EC2 운영 배포와 실제 Robot의 `MISSING` 이미지 E2E는 운영 시스템 변경 권한과 실차 입력이 필요해 실행하지 않았다.
  - 전체 Repository Policy와 Stage 15 최종 증적 테스트는 로컬 Git Bash가 Microsoft Store Python 별칭을 선택하는 환경 문제로 끝까지 실행되지 않았다. 해당 범위의 직접 관련 하위 계약 검증은 위 명령으로 별도 성공했다.

## 영향 및 주의사항

- Robot과 Backend를 함께 새 계약 버전으로 배포해야 한다. 구버전 Backend는 `MISSING` Enum을 거부한다.
- `MISSING`의 nullable 필드는 키를 생략하지 말고 `confidence: null`, `bbox_norm: null`로 보내야 한다.
- V42 적용 없이 새 Backend를 운영하면 `MISSING` 저장이 DB CHECK 또는 NOT NULL 제약으로 실패한다.
- 작업 전부터 존재한 로봇 명령·Mock Robot 관련 변경과 기타 미추적 파일은 수정하거나 정리하지 않았다.

## 미완료 및 후속 작업

- 운영 배포 후 Flyway V42 적용 여부를 확인한다.
- 실제 미설치 사진으로 HTTPS 업로드 → `MISSING` MQTT Incident → DB 관측·이미지 연결 → 관리자 서명 URL JPEG 조회를 검증한다.
- Robot 송신 로그에 `event_id`, `image_id`, `defect_type`, 업로드 HTTP 결과를 함께 남겨 서버 로그와 대조한다.
