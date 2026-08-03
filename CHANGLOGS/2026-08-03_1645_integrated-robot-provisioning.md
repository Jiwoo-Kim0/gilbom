# 변경 로그: 로봇 통합 Provisioning 발급

- 작성 시각: 2026-08-03 16:45 (Asia/Seoul)
- 작업 유형: 기능
- 상태: 부분 완료

## 작업 요약

관리자가 Robot Credential을 한 번 발급하면 Jetson에 필요한 `ROBOT_UUID`, `MQTT_ID`,
MQTT Broker·Username·Client ID·1회 비밀번호, 이미지 `UPLOAD_URL`과 1회
`UPLOAD_API_KEY`를 한 응답에서 받도록 통합 Provisioning 흐름을 구현했다. 이미지 API
Key는 MQTT Credential과 함께 회전하며 서버에는 SHA-256 해시와 prefix만 저장하고
원문은 `no-store` 발급 응답에서 한 번만 제공한다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `embedded/config.yaml` | 수정 | `upload.url`과 `upload.api_key`가 통합 Credential 발급 응답의 `imageUpload` 값이라는 안내와 Secret 1회 표시 규칙을 반영했다. |
| `embedded/scripts/orincar.env.example` | 수정 | `UPLOAD_URL`, `UPLOAD_API_KEY`의 공급 주체를 관리자 통합 Credential 발급 결과로 정정했다. |
| `embedded/var/evidence/README.md` | 수정 | 발급 응답의 이미지 업로드 값을 Jetson 환경변수로 주입하는 연결 방법을 추가했다. |
| `web/.env.example` | 수정 | Jetson에 반환할 전체 이미지 업로드 주소 `GILBOM_STORAGE_UPLOAD_URL` 예시와 LAN 주의사항을 추가했다. |
| `web/backend/src/main/java/com/gilbom/storage/StorageProperties.java` | 수정 | 환경별 외부 이미지 업로드 전체 URL을 보관하는 `uploadUrl` 설정을 추가했다. |
| `web/backend/src/main/java/com/gilbom/storage/auth/RobotImageApiKeySecretGenerator.java` | 추가 | `gik_` namespace와 32바이트 난수로 로봇 이미지 업로드 Secret을 생성한다. |
| `web/backend/src/main/java/com/gilbom/storage/auth/IssuedRobotImageApiKey.java` | 추가 | 발급한 Key ID, 업로드 URL, 1회 원문 Key, 식별 prefix를 내부 발급 결과로 전달한다. |
| `web/backend/src/main/java/com/gilbom/storage/auth/RobotImageApiKeyIssuanceService.java` | 추가 | 기존 활성 이미지 Key를 회수하고 SHA-256 해시만 새 ACTIVE 행으로 저장하며 충돌 시 최대 5회 재생성한다. |
| `web/backend/src/main/java/com/gilbom/robot/credential/MqttCredentialIssuanceService.java` | 수정 | MQTT Credential과 이미지 API Key 발급을 같은 DB 트랜잭션으로 묶고 통합 결과를 반환한다. |
| `web/backend/src/main/java/com/gilbom/robot/data/application/result/IssuedMqttCredential.java` | 수정 | Robot UUID·MQTT ID와 이미지 업로드 URL·1회 API Key를 내부 결과에 추가했다. |
| `web/backend/src/main/java/com/gilbom/robot/data/api/response/MqttCredentialIssuanceResponse.java` | 수정 | `robotId`, `mqttId`, `imageUpload.url`, `imageUpload.apiKey`를 1회 발급 응답 계약에 추가했다. |
| `web/backend/src/main/java/com/gilbom/robot/api/MqttCredentialIssuanceController.java` | 수정 | 서비스의 통합 Provisioning 결과를 새 응답 구조로 매핑한다. |
| `web/backend/src/main/resources/db/migration/V41__allow_robot_image_api_key_issuance.sql` | 추가 | Runtime 애플리케이션 역할이 이미지 API Key 해시 행을 저장하도록 `storage.robot_image_api_key` INSERT 권한을 부여한다. |
| `web/backend/src/test/java/com/gilbom/storage/auth/RobotImageApiKeyIssuanceServiceTest.java` | 추가 | 기존 Key 회수, 해시 저장, 원문 비저장, URL·prefix 반환을 단위 검증한다. |
| `web/backend/src/test/java/com/gilbom/robot/controller/MqttCredentialIssuanceControllerTest.java` | 수정 | UUID·MQTT ID·이미지 URL·1회 API Key가 통합 응답에 포함되는지 검증한다. |
| `web/backend/src/test/java/com/gilbom/robot/integration/MqttCredentialIssuanceIntegrationTest.java` | 수정 | PostGIS 환경에서 이미지 Key 해시·ACTIVE 상태·멱등 응답 Secret 비저장과 Fixture 정리 순서를 검증하도록 보강했다. |
| `web/frontend/gilbom/src/features/robot/robotCredentialApi.ts` | 수정 | 통합 Credential 응답의 Robot 식별자와 이미지 업로드 정보 타입을 추가했다. |
| `web/frontend/gilbom/src/features/robot/RobotCredentialManager.tsx` | 수정 | 발급 성공 창에 Robot UUID, MQTT ID, 이미지 업로드 URL과 1회 API Key를 함께 표시하고 두 Secret 재조회 불가 안내를 추가했다. |
| `web/compose.yaml` | 수정 | 로컬 Backend에 이미지 업로드 외부 URL을 전달하며 기본값을 로컬 Frontend 프록시 주소로 설정했다. |
| `web/compose.prod.yaml` | 수정 | 운영 배포에서 HTTPS 이미지 업로드 전체 URL을 필수 환경변수로 요구한다. |
| `web/docs/openapi-v1.yaml` | 수정 | 통합 Provisioning 발급 의미와 `RobotImageUploadCredential` 응답 Schema를 명세했다. |
| `web/docs/database-migrations.md` | 수정 | V41 Runtime 이미지 API Key INSERT 권한 Migration을 목록에 추가했다. |
| `web/docs/팀원B/V2/로봇 MQTT 환경변수 및 Credential 전달.md` | 수정 | 관리자 화면과 Jetson 환경파일에 Robot UUID·이미지 업로드 URL·API Key를 포함하고 Secret 전달 규칙을 확장했다. |
| `web/docs/팀원B/V2/로봇 등록 및 MQTT Credential 발급.md` | 수정 | 발급 Sequence, JSON 예시와 Jetson 설정을 MQTT·이미지 통합 Provisioning 계약으로 갱신했다. |
| `CHANGLOGS/2026-08-03_1645_integrated-robot-provisioning.md` | 추가 | 통합 발급 구현, 검증 결과와 실제 DB 통합 테스트 미실행 경계를 기록했다. |

## 상세 내용

- `POST /api/v1/robots/{robotId}/credentials`의 성공 응답은 이제 다음 Jetson 설정을
  한 번에 구성할 수 있는 값을 제공한다.
  - `ROBOT_UUID = robotId`
  - `MQTT_ID = mqttId`
  - MQTT Broker·Username·Password·Client ID
  - `UPLOAD_URL = imageUpload.url`
  - `UPLOAD_API_KEY = imageUpload.apiKey`
- 기존 활성 이미지 API Key가 있으면 `REVOKED`로 전환하고 새 Key만 `ACTIVE`로 둔다.
- 이미지 API Key 원문은 DB, 감사 Snapshot, 멱등 응답 Body에 저장하지 않는다.
- 이미지 Key 저장 실패를 포함해 DB 트랜잭션이 Rollback되면 기존 MQTT Credential
  보상 로직이 Broker 계정을 제거하거나 이전 verifier를 복구한다.
- 운영 Compose는 잘못된 localhost URL을 Jetson에 전달하지 않도록
  `GILBOM_STORAGE_UPLOAD_URL`을 필수로 요구한다.
- 기존 Migration V38~V40을 확인하고 중복을 피하기 위해 새 Migration을 V41로 배치했다.

## 검증

- 실행한 명령:
  - `web/backend`에서 `./gradlew.bat compileJava compileTestJava --no-daemon`
  - `web/backend`에서 Image Key Service·MQTT Credential Controller 집중 Test
  - `web/backend`에서 PostGIS Credential 통합 Test 시도
  - `web/frontend/gilbom`에서 `npm test -- --run`, `npm run lint`, `npm run build`
  - `embedded`에서 `uv run --with pytest --with PyYAML==6.0.2 --with requests --with paho-mqtt==2.1.0 pytest test/test_config.py test/test_uploader.py test/test_hardware.py -q -p no:cacheprovider`
  - Python PyYAML OpenAPI Parse와 통합 응답 Required Field 확인
  - Flyway Version 중복·개수·최신 Version 확인
  - `docker compose -f compose.yaml config -q`
  - 대상 파일 `git diff --check`
- 결과:
  - Backend Main·Test Compile 성공.
  - Backend 집중 Test 10건 통과, 실패·Skip 0건.
  - Frontend 단위 Test 44건 통과, ESLint와 TypeScript/Vite production build 성공.
  - Embedded Config·Uploader·Camera 집중 Test 108건 통과.
  - OpenAPI 80 Paths·166 Schemas Parse 성공, 통합 응답 Required Field 일치.
  - Flyway 41개, Version 중복 없음, 최신 V41 확인.
  - Local Compose 설정 검증과 `git diff --check` 통과.
  - Embedded 첫 실행은 `PyYAML`·`requests`, 두 번째 실행은 `paho-mqtt`가 임시 uv
    환경에 없어 각각 수집 실패했으며 필요한 의존성을 모두 명시한 최종 실행은
    108건 전부 통과했다.
- 미실행 항목과 사유:
  - `MqttCredentialIssuanceIntegrationTest` 14건은 Docker Desktop Engine이 꺼져
    Testcontainers가 전부 Skip했다. 제품 실패로 표현하지 않으며 실제 V41 적용과
    PostgreSQL Key 해시 저장·회전 검증은 Docker 가동 환경에서 재실행해야 한다.
  - 실제 Jetson에 Secret을 설치하고 운영 HTTPS 이미지 업로드를 수행하는 E2E는 장비와
    운영 Credential이 없어 실행하지 않았다.

## 영향 및 주의사항

- Credential 회전은 MQTT 비밀번호뿐 아니라 이미지 업로드 API Key도 함께 회전한다.
  기존 Jetson 설정은 두 Secret을 모두 새 값으로 교체해야 한다.
- `GILBOM_STORAGE_UPLOAD_URL`은 Jetson이 실제 접근할 수 있는 전체 HTTPS URL이어야
  한다. 운영 Compose에서 값이 없으면 기동 전 설정 검증이 실패한다.
- 발급 응답은 `Cache-Control: no-store`, `Pragma: no-cache`를 유지하며 두 Secret은
  창을 닫거나 멱등 재요청한 뒤 다시 볼 수 없다.
- 기존 수동 `issue-robot-image-api-key.py`는 비상 운영 도구로 남지만 정상 발급은 관리자
  통합 Credential 흐름을 사용한다.
- 작업 전부터 존재한 Dirty Worktree의 다른 사용자 변경은 수정하거나 되돌리지 않았다.

## 미완료 및 후속 작업

- Docker Engine이 실행되는 환경에서 `MqttCredentialIssuanceIntegrationTest` 14건을
  Skip 없이 재실행한다.
- 운영 `.env`에 `GILBOM_STORAGE_UPLOAD_URL=https://<host>/api/v1/images`를 설정하고
  V41 적용 후 실제 발급 응답과 DB 해시를 대조한다.
- Jetson `orincar.env`에 통합 발급 값을 설치해 이미지 업로드 → `image_id` → MQTT
  Incident → 브라우저 이미지 조회 종단 간 흐름을 검증한다.
