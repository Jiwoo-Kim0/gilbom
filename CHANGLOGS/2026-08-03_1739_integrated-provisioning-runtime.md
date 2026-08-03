# 변경 로그: 통합 Provisioning Runtime 반영

- 작성 시각: 2026-08-03 17:39 (Asia/Seoul)
- 작업 유형: 설정
- 상태: 완료

## 작업 요약

병합 전 번호의 Flyway V36·V37이 적용된 로컬 PostgreSQL 데이터를 보존하면서 현재
정본 V36~V38과 이력을 정합화했다. 이후 통합 Provisioning Backend 이미지를 실행해
Flyway V39~V41을 정상 적용했으며, Credential 발급 응답이 `robotId`, `mqttId`, MQTT
연결 정보, `imageUpload.url`, `imageUpload.apiKey`를 함께 반환하는 흐름을 실제 PostGIS
통합 테스트로 검증했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `CHANGLOGS/2026-08-03_1739_integrated-provisioning-runtime.md` | 추가 | 로컬 DB 백업, V36~V38 이력 정합화, V39~V41 적용, 통합 Backend 재기동과 검증 결과를 기록 |

## 상세 내용

- 기존 DB의 Flyway 이력은 병합 전 번호 체계였다.
  - V36: `relax zone id format`
  - V37: `soft delete terminal missions`
- 현재 정본의 물리 효과와 기존 DB를 대조했다.
  - 현재 V37의 Zone 제약 해제와 `VARCHAR(50)` 확장은 이미 적용돼 있었다.
  - 현재 V38의 Mission 논리 삭제 컬럼·제약·인덱스와 `COMPLETE_MISSION` 명령 제약은
    이미 적용돼 있었다.
  - 현재 V36의 결함 유형 3종 제약은 미적용 상태였다.
- DB 정합화 전에 PostgreSQL 영속 Volume 내부에 전체 Custom-format 백업을 생성했다.
  - 경로: `/var/lib/postgresql/data/pre_integrated_provisioning_20260803_173116.dump`
  - 크기: 약 1009 KiB
  - SHA-256: `91cf581b9bd4541967b208ceb36f9e71ddc14d5c894404bf888fae57f8139b81`
  - `pg_restore --list`로 백업 판독 가능 여부를 확인했다.
- 별도 임시 DB에서 통합 이미지를 기동해 V1~V41을 모두 적용하고 정본 체크섬을
  서버 자체로 산출했다. 임시 Container와 DB는 검증 후 제거했다.
- 기존 데이터에서 제거 대상 결함 유형 4종은 0행이었다. 현재 V36 SQL을 단일
  Transaction으로 적용하고 V36~V38 Flyway 이력을 실제 물리 효과와 정본 체크섬에
  맞췄다.
  - V36 `932544246`: 결함 유형 3종 축소
  - V37 `1712920896`: Zone ID 형식 제약 해제
  - V38 `360473619`: 종료 Mission 논리 삭제
- `gilbom-backend:integrated-20260803` 이미지를 `gilbom-backend:local` 실행 태그로
  전환하고 Backend를 재생성했다. 호환 이미지는
  `gilbom-backend:db-v37-compatible` 태그로 유지했다.
- Backend 기동 시 정본 Migration 41개 검증에 성공하고 V39~V41 세 건만 신규
  적용했다. Runtime 역할의 `storage.robot_image_api_key` INSERT 권한도 활성화됐다.
- 사용자 Robot Credential은 1회 Secret을 다시 바꾸지 않도록 Runtime 검증 과정에서
  추가 회전하지 않았다.

## 검증

- 실행한 명령:
  - `pg_dump -Fc` 및 `pg_restore --list`
  - 기존 DB Flyway V34~V41 이력, 결함·Zone·Mission 제약과 Runtime 권한 조회
  - 임시 DB에서 통합 Backend 기동 및 Flyway V1~V41 적용
  - 현재 V36 SQL과 V36~V38 이력 정합화 Transaction
  - `docker compose -f compose.yaml up -d --no-deps --force-recreate backend`
  - `GET http://127.0.0.1:8080/actuator/health`
  - `./gradlew.bat test --no-daemon --rerun-tasks --tests com.gilbom.robot.integration.MqttCredentialIssuanceIntegrationTest`
- 결과:
  - Backend·Frontend·DB 모두 `healthy`
  - Backend Actuator 상태 `UP`
  - 실행 Backend Image: `sha256:0ba6de24b54d2d9083df9945b7e573259cab24af3b8062d2343005c4fb730e53`
  - Flyway V1~V41 검증 성공, V39~V41 적용 성공, 최신 Version V41
  - Runtime 이미지 API Key 권한: INSERT·SELECT·UPDATE 모두 `true`
  - `MqttCredentialIssuanceIntegrationTest`: 14건 통과, 실패 0건, Skip 0건
- 미실행 항목과 사유:
  - 실제 로봇 Credential 회전은 MQTT·이미지 Secret을 다시 무효화하는 상태 변경이며
    원문이 한 번만 표시되므로 자동 실행하지 않았다. 사용자가 새로고침 후 한 번만
    회전해 응답 값을 안전한 경로에 저장해야 한다.
  - 실제 Jetson 이미지 업로드 E2E는 Jetson과 배포 HTTPS Credential이 필요한 별도
    장비 검증 범위라 실행하지 않았다.

## 영향 및 주의사항

- Credential 회전 응답은 이제 `robotId`를 Jetson의 `ROBOT_UUID`로, `mqttId`를
  `MQTT_ID`로 전달하며 MQTT Secret과 이미지 업로드 URL·API Key를 함께 제공한다.
- 이전에 ROUTE ERROR가 발생한 동안 여러 번 회전한 Credential은 마지막 발급분을
  제외하고 폐기됐을 수 있다. 통합 화면에서 새로 한 번만 회전한 뒤 두 Secret을 모두
  Jetson 설정에 반영해야 한다.
- 백업 파일에는 인증·업무 데이터가 포함될 수 있으므로 외부 공유하거나 저장소에
  복사하지 않는다.
- `docker compose down --volumes`는 PostgreSQL뿐 아니라 이미지 Volume까지 제거할 수
  있으므로 사용하지 않는다.

## 미완료 및 후속 작업

- 브라우저를 새로고침한 뒤 Credential 회전을 한 번만 수행하고 `Robot UUID`,
  `MQTT ID`, MQTT 연결 정보, 이미지 업로드 URL·API Key가 모두 표시되는지 확인한다.
- 발급 값을 Jetson에 설치한 후 HTTPS 이미지 업로드 → Volume JPEG →
  `storage.robot_image` 메타데이터 저장 흐름을 EC2에서 검증한다.
