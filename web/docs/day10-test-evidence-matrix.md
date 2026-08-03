# Day 10 테스트 증거 매트릭스

기준일: 2026-08-03

## 1. 목적과 판정 원칙

이 문서는 Day 10 통합·보안·장애 테스트를 기존 자동화와 대조해 중복 구현을
막고, 새 테스트 로직이 필요한 항목과 실행·증거 연결만 필요한 항목을 구분한다.

- `예`: 반복 실행 가능한 자동 판정 로직이 있고 현재 CI 또는 격리 환경에서
  실행된다.
- `부분`: 자동 판정 로직이나 정적 계약 검사는 있지만 CI 연결, 실제 외부 경계,
  호스트 수명주기 또는 PASS 증거 보존이 부족하다.
- `아니오`: 요구사항을 직접 검증하는 자동 테스트가 없다.
- `N/A`: 현재 운영 구조에는 대상 자체가 없다. 대상이 도입되면 다시 필수
  게이트로 전환한다.
- PASS 증거는 실행 결과로 생성된 파일만 인정한다. 테스트 소스와 Runbook은
  구현 근거이지 PASS 증거가 아니다.
- `.artifacts/backend-ci/build/test-results/**`와
  `.artifacts/security-regression/**`는 Jenkins 최종 archive 목록에 포함한다.
  장기 판정은 status marker, JUnit XML과 기준선 JSON을 함께 사용한다.
- 이 단계의 최종 집계 증거는
  `.artifacts/security-regression/baseline-summary.json`이며 모든 필수 gate가
  `PASS`일 때만 생성 Stage가 통과한다.
- `(예정)`은 아직 존재하는 PASS가 아니라 이후 구현이 생성해야 할 고정
  증거 경로다.

실행 환경은 다음 의미로 사용한다.

- `CI`: Jenkins의 TLS DinD 안에서 실행하며 운영 Docker Socket과 운영
  Credential을 사용하지 않는다.
- `격리 Compose`: 운영과 다른 Compose project, network, volume에서 실행한다.
- `스테이징 EC2`: 운영과 같은 호스트·systemd·TLS 구성을 복제한 테스트
  인스턴스다.
- `운영`: 실패 유도 없이 read-only 조회나 승인된 배포·Rollback 경계만
  검증한다.

## 2. 애플리케이션·인증·권한

| ID | 검증 항목 | 기존 자동 테스트 | 추가 구현 필요 여부 | 실행 환경 | PASS 증거 파일 | 운영 영향 |
| --- | --- | --- | --- | --- | --- | --- |
| A01 | 로그인 성공·실패·임시 잠금·동일 오류 계약 | 예 | 없음 | CI | `.artifacts/ci-results/backend-test.status`, `.artifacts/backend-ci/build/test-results/test/*.xml` | 없음 |
| A02 | MFA 등록·성공·실패·복구 코드·복구 코드 중복 사용 | 예 | 없음 | CI | A01과 동일 | 없음 |
| A03 | TOTP 이전·현재·다음 time-step만 허용, 범위 밖과 같은 step 재사용 차단 | 예 | 없음. `TotpServiceTest`가 기준 step의 `-1/0/+1` 허용과 `-2/+2` 거부를 각각 명시한다. | CI | A01과 동일 | 없음 |
| A04 | 호스트 시간 오차·NTP 이상 상태에서 TOTP 허용 범위 확인 | 아니오 | 필요. 애플리케이션 시계를 임의 변경하지 말고 주입 가능한 Clock 기반 테스트와 스테이징 NTP 상태 증거를 추가한다. | CI + 스테이징 EC2 | `(예정) .artifacts/day10/totp-clock-drift.json` | CI는 없음, 스테이징은 read-only 시간 조회 |
| A05 | 로그아웃·idle/absolute 만료·Session ID와 CSRF 교체·이전 Session 재사용 차단 | 예 | 없음. 기존 HTTP/DB Runner를 원격 DinD 포트와 전용 DB 격리에 연결했다. | CI + 격리 Compose | `.artifacts/ci-results/auth-security-integration.status`, `.artifacts/security-regression/auth-security-integration.json` | 없음 |
| A06 | 동시 세션 제한·비밀번호/권한/Scope 변경 시 기존 세션과 SSE 즉시 폐기 | 예 | 없음. DB 권한 변경의 Session 삭제와 SSE의 다음 publish/heartbeat 선행 Session 재검사를 함께 검증한다. | CI | `.artifacts/ci-results/backend-test.status`, `.artifacts/ci-results/security-acceptance.status` | 없음 |
| A07 | 역할별 401·403, ALLOW-DENY 우선순위, 만료 권한, 마지막 ADMIN 보호 | 예 | 없음 | CI | `.artifacts/ci-results/security-acceptance.status`, `.artifacts/backend-ci/build/test-results/securityAcceptanceTest/*.xml` | 없음 |
| A08 | 객체 Scope, 목록·상세·지도·요약 IDOR, SSE 재검사와 다중 Page 대량 결과 Scope | 예 | 없음. 240개 추가 Robot을 섞은 3 Page 회귀에서 허용 121개만 목록·요약·지도에 나타나고 비허용 상세는 404인지 검증한다. | CI + 격리 Compose | A07과 동일 | 없음 |
| A09 | CSRF 누락·Session 회전 전 Token·한 글자 변조 Token 차단 | 예 | 없음. MockMvc 변조 case와 임시 DB HTTP Runner의 구 Token/변조 Token case를 모두 추가했다. | CI + 격리 Compose | `.artifacts/ci-results/backend-test.status`, `.artifacts/ci-results/auth-security-integration.status`, `.artifacts/security-regression/auth-security-integration.json` | 없음 |
| A10 | 운영 CORS exact allowlist와 Credential preflight | 예 | 운영 비파괴 수집기가 비허용 Origin을 실제 HTTPS OPTIONS로 확인한다. CORS 활성 시 runtime exact allowlist의 허용 Origin과 credential header도 함께 확인한다. 운영 실행 증거 생성이 남았다. | CI + 운영 read-only | `.artifacts/ci-results/backend-test.status`; `(예정) /tmp/day10-production-readonly.json` | read-only OPTIONS, 세션·감사 행 미생성 |
| A11 | 운영 Cookie의 `Secure`, `HttpOnly`, `SameSite`, Path와 로그아웃 만료 | 부분 | 운영 이미지를 Cosign으로 재검증하고 실제 Backend의 `ec2,docker` Profile 활성화를 확인해 `__Host-`, Secure, HttpOnly, SameSite=Lax, Path=/ 정책을 증명한다. 익명 세션 생성을 피하기 위해 운영 `Set-Cookie` 요청은 수행하지 않으며 실제 Header 검증은 스테이징에 유지한다. | CI + 운영 read-only + 향후 스테이징 | `.artifacts/ci-results/backend-test.status`; `(예정) /tmp/day10-production-readonly.json`, `.artifacts/day10/staging/cookie-smoke.json` | 운영 변경 없음 |
| A12 | HSTS·CSP·nosniff·frame·referrer·permissions 등 보안 응답 Header | 예 | 테스트 로직 추가 없음. `scripts/verify-day8-ec2.sh`의 Header 결과를 파일로 보존하도록 실행 wrapper만 필요 | CI + 운영 read-only | `.artifacts/ci-results/frontend-runtime-image.status`; `(예정) .artifacts/day10/production/security-headers.json` | 운영 read-only HTTPS |
| A13 | 악성 HTML·Script를 실행 가능한 DOM으로 렌더링하지 않음 | 예 | 없음 | CI | `.artifacts/ci-results/frontend-test.status` | 없음 |
| A14 | 과도한 Body/Image, 잘못된 기간, 미래 시각, Page size와 Pagination 경계 | 예 | 없음 | CI | `.artifacts/ci-results/backend-test.status`, `.artifacts/ci-results/frontend-test.status` | 없음 |
| A15 | 멱등 키의 동일 Body replay와 상이 Body 충돌, 동시 요청 단일 처리 | 예 | 없음. `ReportDomainServiceIntegrationTest`가 같은 Key/다른 Body의 `IDEMPOTENCY_KEY_REUSED` 거부를 이미 검증한다. | CI | `.artifacts/ci-results/backend-test.status`, `.artifacts/ci-results/frontend-test.status` | 없음 |
| A16 | 확장자·Content-Type이 아니라 실제 JPEG byte 형식으로 이미지 검증 | 예 | 없음 | CI | `.artifacts/ci-results/backend-test.status` | 없음 |
| A17 | 운영 Profile의 CORS·Cookie와 필수 Secret·DB 설정 오류 fail-fast/fail-closed | 예 | 없음. Profile 단위 CORS exact allowlist, 운영 Cookie 속성, 암호화·rate-limit 필수 설정의 Context 기동 실패를 기존 테스트로 재사용한다. 실제 외부 HTTPS 검증은 A10·A11의 스테이징 경계로 별도 유지한다. | CI + 격리 Compose | `.artifacts/ci-results/backend-test.status`, `.artifacts/ci-results/report-llm-fallback.status` | 없음 |

주요 구현 근거:

- 인증 전체 HTTP/DB 시나리오:
  `scripts/auth-security-integration-test.py`
- TOTP와 복구:
  `backend/src/test/java/com/gilbom/auth/mfa/TotpServiceTest.java`,
  `backend/src/test/java/com/gilbom/auth/mfa/MfaAuthenticationCompletionServiceTest.java`
- 권한·세션 폐기·마지막 ADMIN:
  `backend/src/test/java/com/gilbom/auth/mfa/AuthenticationUserRepositoryIntegrationTest.java`
- 객체와 SSE Scope:
  `backend/src/test/java/com/gilbom/defect/integration/DefectQueryScopeIntegrationTest.java`,
  `backend/src/test/java/com/gilbom/robot/integration/RobotQueryScopeIntegrationTest.java`,
  `backend/src/test/java/com/gilbom/defect/realtime/DefectSseServiceTest.java`,
  `backend/src/test/java/com/gilbom/robot/realtime/RobotSseServiceTest.java`
- CORS·Header·CSRF:
  `backend/src/test/java/com/gilbom/security/CorsEnabledSecurityConfigTest.java`,
  `backend/src/test/java/com/gilbom/security/SecurityConfigTest.java`
- 이미지 실제 byte 검사:
  `backend/src/test/java/com/gilbom/storage/ImageUploadServiceTest.java`

## 3. 보고서·LLM·감사·MQTT

| ID | 검증 항목 | 기존 자동 테스트 | 추가 구현 필요 여부 | 실행 환경 | PASS 증거 파일 | 운영 영향 |
| --- | --- | --- | --- | --- | --- | --- |
| B01 | LLM Timeout·빈 응답·비 JSON·잘못된 Schema와 Template fallback | 예 | 없음 | CI + 격리 Compose | `.artifacts/ci-results/backend-test.status`, `.artifacts/ci-results/report-llm-fallback.status` | 없음 |
| B02 | 보고서 생성·수정·검토 요청·반려·승인, 자기 승인 차단, 승인본 변경 차단 | 예 | 없음 | CI + 격리 Compose | B01과 동일 | 없음 |
| B03 | 감사 로그 필수 기록 실패 시 업무 Transaction Rollback, append-only, 민감정보 redaction | 예 | 없음 | CI | `.artifacts/ci-results/security-acceptance.status`, `.artifacts/ci-results/csrf-permission-audit.status`, `.artifacts/ci-results/jenkins-log-secret.status` | 없음 |
| B04 | MQTT TTL·Replay·Retain·멱등성과 실제 TLS Broker 왕복 | 예 | 없음. 기존 `mqttBrokerE2E`를 DinD TLS Broker와 임시 Robot Credential에 연결하고 종료 시 Credential 삭제를 검증한다. | CI + 격리 Compose | `.artifacts/ci-results/mqtt-broker-e2e.status`, `.artifacts/security-regression/mqtt-broker-e2e.json`, `.artifacts/backend-ci/build/test-results/mqttBrokerE2E/*.xml` | 없음 |
| B05 | Robot별 MQTT Topic ACL 허용·타 Robot/역방향 Topic 거부 | 예 | 없음. 임시 Robot Credential로 실제 TLS Broker에 허용 publish/subscribe를 먼저 확인한 뒤 타 Robot namespace와 역방향 topic의 publish/subscribe 네 경계를 모두 거부해야 PASS다. | CI + 격리 Compose | `.artifacts/ci-results/mqtt-broker-e2e.status`, `.artifacts/security-regression/mqtt-broker-e2e.json` | 없음 |
| B06 | 인증·RBAC·Session을 포함한 대용량 데이터와 k6 부하 | 아니오 | 추후 별도 성능 검증 단계로 연기했다. 기존 `passwordHashLoadTest`는 Argon2 단일 부하라 이 요구를 대체하지 않으며, 구현 전에는 성능·수용량 PASS를 주장하지 않는다. | 별도 k6 EC2 + 스테이징 EC2 | `(추후) .artifacts/performance/k6/summary.json`, `(추후) .artifacts/performance/k6/thresholds.json` | 운영에서는 실행 금지, 스테이징 자원 부하 |

주요 구현 근거:

- LLM 오류와 결과 검증:
  `backend/src/test/java/com/gilbom/llm/GmsLlmClientTest.java`,
  `backend/src/test/java/com/gilbom/report/generation/ReportLlmResultValidatorTest.java`,
  `scripts/container-resilience-smoke.sh`
- 보고서 Workflow:
  `backend/src/test/java/com/gilbom/report/integration/ReportDomainServiceIntegrationTest.java`,
  `backend/src/test/java/com/gilbom/report/integration/ReportApplicationServiceIntegrationTest.java`
- 감사:
  `backend/src/test/java/com/gilbom/audit/AuditLogServiceTest.java`,
  `backend/src/test/java/com/gilbom/audit/integration/AuditLogIntegrationTest.java`
- MQTT 실제 Broker E2E:
  `backend/src/test/java/com/gilbom/robot/mqtt/client/MqttPublisherBrokerE2E.java`,
  `backend/src/test/java/com/gilbom/robot/mqtt/client/MqttInboundBrokerE2E.java`,
  `scripts/run-mqtt-broker-e2e-ci.sh`
- MQTT ACL:
  `infra/mosquitto/acl`,
  `infra/mosquitto/README.md`

## 4. CI/CD·호스트·복구

| ID | 검증 항목 | 기존 자동 테스트 | 추가 구현 필요 여부 | 실행 환경 | PASS 증거 파일 | 운영 영향 |
| --- | --- | --- | --- | --- | --- | --- |
| C01 | CI/CD 성공·실패 알림, 수동 배포 승인, 조건부 Rollback A/B/C | 예 | 없음 | CI + 격리 Compose + 승인된 운영 배포 | `.artifacts/cicd-notification/summary.json`, `.artifacts/deploy/release-manifest.json`, `.artifacts/rollback-drill/summary.json`, `stage15-final-evidence.json` | 훈련은 없음, 실제 배포는 승인된 운영 변경 |
| C02 | DB·Robot image·Report PDF Volume 보존과 이전 Artifact reload | 예 | 없음 | CI + 격리 Compose | `.artifacts/ci-results/volume-persistence.status`, `.artifacts/rollback-drill/summary.json` | 없음 |
| C03 | EC2 재부팅 후 Docker·Compose·Timer 자동 시작 | 부분 | `restart: unless-stopped`와 복구 검사는 있으나 실제 호스트 reboot 증거가 없다. 스테이징 전용 reboot drill 필요 | 스테이징 EC2 | `(예정) .artifacts/day10/staging/reboot-recovery.json` | 스테이징 중단 발생, 운영 실행 금지 |
| C04 | 인증서 갱신 Timer·webroot·Nginx reload와 실제 ACME dry-run | 부분 | 정적 계약과 Timer active 검사는 존재한다. 스테이징에서 `certbot renew --dry-run` 결과와 갱신 전후 HTTPS를 보존해야 한다. | CI + 스테이징 EC2 | `.artifacts/ci-results/repository-policy.status`; `(예정) .artifacts/day10/staging/certbot-dry-run.txt` | 스테이징 ACME 요청·Nginx reload 가능 |
| C05 | S3 최소권한과 Block Public Access | N/A | 현재 저장소는 `local-disk` named volume이다. S3 전환 PR에서 IAM policy, bucket public access와 presigned URL test를 필수로 추가 | 향후 CI + 스테이징 AWS | `(향후) .artifacts/day10/aws/s3-access.json` | 현재 없음 |
| C06 | DB·이미지·PDF·MQTT Volume 백업 전체 복원 재확인 | 예 | 없음. 2026-08-03 실제 암호화 bundle을 새 내부 Network·DB container·Named Volume에 복원해 DB table별 정규화 SHA-256, sequence 상태, Robot image·승인 PDF의 DB 참조/Volume SHA-256 완전 일치를 확인했다. | 격리 Compose | `.artifacts/day10/restore/summary.json`, `.artifacts/day10/restore/restore.log`, `docs/day10-stage6-recovery-status.json` | 없음. 운영 DB 자동 복원 금지. 새 Volume backup 생성 시 짧은 서비스 quiesce 발생 |
| C07 | 외부 노출 포트와 AWS Security Group | 예 | 운영 비파괴 수집기가 host wildcard listener와 IMDSv2의 실제 연결 SG ID를 고정하고 AWS Describe 결과를 대조한다. 80/443 외 world-open, 내부 port Inbound, group 불일치를 fail-closed한다. 운영 실행 증거 생성이 남았다. | 운영 EC2 + AWS Describe | `(예정) /tmp/day10-production-readonly.json` | read-only `ss`, IMDSv2, AWS Describe |
| C08 | Backend·Frontend dependency와 image Critical/High 취약점 | 예 | 없음 | CI | `.artifacts/source-security/dependency-policy-summary.json`, `.artifacts/image-security/policy-summary.json`, `.artifacts/ci-results/sbom-cve.status` | 없음 |
| C09 | Cosign으로 release archive 서명과 배포 전 검증 | 예 | 없음. Registry가 아닌 실제 전달물 `images.tar.zst`를 Blob 서명하고 정상·변조·wrong-key를 검사한다. root 승격·preflight·배포·Rollback load가 EC2 고정 공개키로 재검증한다. | CI + EC2 root 승격/배포 | `release/images.tar.zst.sigstore.json`, `.artifacts/cosign/summary.json`, `.artifacts/ci-results/cosign-blob-signature.status`, preflight·Release Manifest의 `artifact.signature` | CI 서명은 없음, EC2 검증은 read-only. 실패 시 배포 차단 |
| C10 | 스테이징 EC2에서 동일 SHA 배포·장애 감지·Rollback 재훈련 | 부분 | 격리 Compose의 A/B/C Rollback 자동화는 존재한다. 운영과 분리된 스테이징 배포 Job·Artifact 경로·승인 경계와 실제 스테이징 실행 증거가 필요하다. | 스테이징 EC2 | `(예정) .artifacts/day10/staging/rollback-rehearsal.json` | 스테이징 서비스 변경·일시 중단, 운영 영향 없음 |

주요 구현 근거:

- Rollback 훈련:
  `Jenkinsfile.rollback-drill`,
  `scripts/run-production-rollback-drill.sh`,
  `scripts/verify-production-rollback-drill-evidence.sh`
- 운영·Stage 15 증거:
  `scripts/verify-day8-ec2.sh`,
  `scripts/collect-stage15-host-evidence.sh`,
  `scripts/generate-stage15-final-evidence.py`
- Volume 복구:
  `scripts/container-resilience-smoke.sh`,
  `scripts/verify-day8-persistence.sh`
- 인증서 자동화:
  `scripts/verify-certbot-automation.sh`,
  `infra/systemd/gilbom-certificate-renew.timer`
- 취약점:
  `scripts/verify-source-security.sh`,
  `scripts/scan-image-security.sh`

## 5. 실제 누락과 다음 구현 경계

### 새 테스트 로직이 필요한 항목

1. `C03` 스테이징 EC2 reboot recovery drill
2. `A10`, `A11`, `C04`, `C07` 실제 외부/호스트 경계 검증
3. `A04` 스테이징 NTP 상태 증거
4. `C10` 운영과 분리된 스테이징 배포·Rollback 실행 경계

`B06` k6는 이번 완료 범위에서 제외하고 별도 성능 검증 단계로 연기했다.
`C06`은 새 형식의 실제 backup `20260803T070355Z-gilbom`을 대상으로 한
격리 복원과 JSON 증거 생성을 완료해 PASS로 판정했다.

### 새 테스트를 만들지 않고 연결만 할 항목

`B04` TLS MQTT Stage, `B05` 실제 ACL 부정 테스트, `A05` 인증 임시 DB Stage,
`A09` CSRF 변조·구 Token과 Backend JUnit archive 연결은 2026-08-03에
완료했다. 남은 연결 작업은 다음과 같다.

1. Frontend TAP 결과를
   JUnit 또는 JSON으로 변환해 보존한다.
2. 스테이징·운영 read-only 스크립트의 console PASS를 고정 JSON/TXT Artifact로
   회수한다.

### 현재 중복 구현하지 않을 항목

로그인·잠금·MFA·복구 코드, Session 만료,
동시 Session·권한 변경 폐기, RBAC·DENY·마지막 ADMIN, 객체·SSE Scope,
입력 제한, XSS 렌더링, LLM fallback, Report Workflow, 감사 Transaction,
이미지 byte 검사, Rollback A/B/C, Volume 보존, Critical/High 검사는 기존
자동 테스트를 그대로 사용한다.

## 6. 권장 실행 순서

1. CI 증거 보존과 기존 MQTT/Auth Runner 연결
2. 새 형식의 실제 backup으로 격리 Compose restore
3. 스테이징 EC2의 CORS/Cookie/포트/인증서/NTP read-only 검사
4. 스테이징 reboot recovery
5. 스테이징 동일 SHA 배포·Rollback 재훈련
6. 모든 스테이징 증거가 PASS한 뒤 운영에는 외부 HTTPS와 host 상태의
   read-only 재검사만 수행

운영에서 의도적으로 로그인 잠금, 장애, DB 복원, 재부팅 또는 부하를 유발하지
않는다. 운영 변경이 필요한 검증은 기존 수동 승인 배포·Rollback Job의 범위를
넘기지 않는다.

## 7. 3단계 설계·구현 최종 코멘트

이번 단계는 기존 회귀 테스트와 실제로 겹치지 않는 경계만 추가했다.

- TOTP는 허용 window를 이전·현재·다음 step으로 고정하고, 그 바깥인
  `-2`, `+2` step은 명시적으로 거부한다.
- CSRF는 단위 수준의 변조 검사와 임시 DB·Backend를 사용한 실제 HTTP 검사를
  함께 수행한다. Session ID가 교체된 뒤의 이전 token과 형식이 변조된 token은
  모두 `403`이어야 한다. 변조 token decode 오류가 `500`으로 전파되던 경로는
  일반 CSRF 불일치로 안전하게 처리하도록 수정했다.
- 권한 변경 뒤 SSE는 다음 event를 보내기 직전에 Scope를 다시 조회하고,
  권한이 사라졌다면 payload를 전송하지 않고 subscription을 즉시 제거한다.
- Robot Scope/IDOR는 240개 추가 객체를 여러 page로 조회해 허용 객체의 누락,
  숨겨진 객체의 섞임, summary·map·detail 간 Scope 불일치를 함께 검사한다.
- MQTT ACL은 CLI exit code만 신뢰하지 않는다. 실제 TLS Broker에서 정상
  publish/subscribe 전달을 positive control로 확인한 뒤, 타 Robot namespace와
  역방향 topic의 publish/subscribe가 실제로 전달되지 않는지를 검증한다.
- 동일 멱등 key에 다른 body를 보내는 경우와 운영 Profile의
  CORS·Cookie·필수 Secret fail-fast는 기존 자동 테스트가 이미 해당 경계를
  검증하므로 새 테스트를 중복 추가하지 않았다.
- Jenkins 증거 집계는 CSRF 두 경계와 실제 Broker ACL 네 경계가 모두
  `true`인 경우에만 PASS하도록 fail-closed 조건을 추가했다.

모든 동적 검사는 로컬 Docker/DinD 호환 Runner에서 임시 DB, 임시 MQTT
Credential과 격리 Compose 자원만 사용하며 운영 시스템에는 접근하거나
변경하지 않는다. 대량 객체 Scope 회귀는 권한 정확성 검사이지 성능 시험은
아니므로 `B06` k6 부하 기준선은 별도 단계로 남긴다. 외부 HTTPS 관점의
CORS·Cookie, NTP와 운영 Profile host 증거 역시 스테이징 read-only 검증으로
분리한다.

## 8. 4단계 설계·구현 최종 코멘트

이번 단계는 Registry tag가 아니라 EC2에 실제 전달되고 `docker load`되는
`images.tar.zst`를 서명 대상으로 선택했다. 따라서 전송 형식과 배포 입력이
일치하며, archive가 전송·보관 중 바뀌면 SHA-256뿐 아니라 Jenkins에만 있는
Private key의 서명 검증에서도 차단된다.

- Cosign `v3.1.2`와 Linux `amd64`·`arm64` 바이너리 SHA-256을 함께 고정했다.
- CI는 SBOM·CVE gate 뒤 archive를 생성하고 Jenkins Secret File과 Secret Text
  Credential을 서명 Stage에서만 사용한다.
- 정상 서명 확인뿐 아니라 변조 archive와 임시 wrong key가 반드시 거부되는지를
  같은 Stage에서 부정 테스트한다.
- Bundle과 공개키 fingerprint를 Build Metadata에 결속하고 Bundle 자체도
  `SHA256SUMS`와 immutable Artifact에 포함했다.
- EC2에는 `root:root` 공개키와 검증기만 설치한다. incoming 원자 승격,
  배포 preflight, root 배포기의 `docker load` 직전, 이전 archive Rollback load
  직전에 같은 공개키로 다시 검증한다.
- 검증 실패는 `docker load`, `image.env`, Compose, DB에 손대기 전에
  fail-closed되며, Release Manifest에는 Secret 없이 Cosign version·공개키
  fingerprint·Bundle hash만 남긴다.

폐쇄형 key-pair 운영이라 공개 Rekor entry를 요구하지 않는다.
`--insecure-ignore-tlog`는 transparency log 부재만 허용하며 서명 검증 자체를
생략하지 않는다. 이 모델의 신뢰 기준은 Jenkins Credential의 Private key와
EC2 root 소유 공개키다. 단일 공개키를 임의로 교체하면 이전 Rollback Artifact가
검증되지 않으므로 키 회전은 다중 키 정책을 도입하거나 보존 Artifact를 재설계한
뒤 별도 변경으로 수행한다.

## 9. 6단계 설계·구현 최종 코멘트

기존 복원 스크립트가 이미 운영과 분리된 임시 PostgreSQL·Network·Volume을
사용했으므로 별도 복원기를 중복 구현하지 않고 검증 강도를 높였다.

- Volume backup은 writer인 Frontend·Backend·MQTT를 quiesce한 동일 시점에
  애플리케이션 10개 schema의 모든 base table을 행 단위 JSON으로 정규화하고,
  table별 행 수·SHA-256과 sequence의 `last_value`·`is_called` 상태를
  `database.content.sha256`에 고정한다.
- Robot image와 승인 PDF는 DB에 저장된 SHA-256·storage key와 실제 Named
  Volume 전체 파일 hash를 각각 기록한다. DB가 참조하는 파일이 없거나
  변조됐다면 backup 단계부터 실패한다.
- Restore Drill은 새 internal Docker Network, 새 PostgreSQL container와 새
  Named Volume만 만든다. `pg_restore --exit-on-error` 뒤 같은 DB manifest와
  file manifest를 재생성해 backup 시점 값과 byte-for-byte로 비교한다.
- PostgreSQL 공식 entrypoint의 초기화용 임시 server가 먼저 readiness를
  반환하므로 최종 초기화 marker와 `pg_isready`를 함께 확인한다.
  `postgis/postgis`가 bootstrap DB에 선생성하는 PostGIS/tiger 객체와 dump가
  충돌하지 않도록 실제 복원 DB는 `template0`에서 별도로 만든다.
- `summary.json`은 단순 exit code만 보지 않고 bundle checksum, DB content,
  Robot image, 승인 PDF의 필수 PASS marker가 모두 존재해야만 PASS가 된다.
- 평문 dump와 archive는 임시 작업 디렉터리에만 존재하고 종료 시 제거한다.
  복호화 Private key는 Git·운영 EC2에 두지 않으며 키 보관 환경에서 Drill한다.
- 이전 형식 backup은 새 DB/PDF hash manifest가 없으므로 완전한 C06 증거로
  승격하지 않는다. 새 형식 bundle의 실제 실행에서는 DB 41개 table과 1개
  sequence, Robot image 2개, 승인 PDF 0개의 hash·참조가 모두 일치했고
  `.artifacts/day10/restore/summary.json`이 최종 PASS를 기록했다.

현재 확인 가능한 스테이징 EC2가 없으므로 실제 reboot, ACME dry-run과
스테이징 Rollback은 PASS로 간주하지 않고 `PENDING_NO_STAGING`으로 남긴다.
운영 DB 복원, 운영 EC2 reboot와 운영 장애 주입은 이번 단계에서 수행하지 않는다.
