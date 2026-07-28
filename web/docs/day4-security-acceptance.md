# Day 4 보안 완료 기준

Day 4의 RBAC, CSRF, 객체 범위, 감사 로그, MFA 완료 여부는 아래 명령으로 판정한다.

```bash
cd backend
./gradlew securityAcceptanceTest
```

`securityAcceptanceTest`는 `security-acceptance` JUnit 태그가 지정된 테스트만 실행한다.
일반 `test` 작업에서도 이 테스트들은 계속 실행되므로, 별도 게이트와 전체 회귀 테스트가
서로 다른 결과를 만들지 않는다.

Jenkins는 이미지 빌드 전에 Docker socket을 연결한 Java 21 컨테이너에서 이 태스크를
실행한다. 따라서 Docker build 안에서 Testcontainers 테스트가 건너뛰어지는 것과 무관하게
실제 PostgreSQL 검증을 배포 게이트로 사용한다.

| 완료 기준 | 자동화 테스트 |
|---|---|
| 보호된 GET 비로그인 → 401 | `RobotQueryControllerTest.rejectsUnauthenticatedListRequest` |
| 유효한 CSRF가 있지만 permission 없음 → 403 | `RobotRegistrationControllerTest.requiresRobotRegisterPermission` |
| 인증 사용자의 상태 변경 요청에 CSRF 없음 → 403 | `RobotRegistrationControllerTest.requiresCsrfTokenForRegistration` |
| 단건 객체 범위 밖 → 404 | `RobotQueryControllerTest.mapsOutOfScopeDetailToSameNotFoundResponse`, `RobotQueryScopeIntegrationTest.appliesSameSqlScopeToListSummaryMapAndDetail` |
| 목록·통계에서 미배정 로봇 데이터 미조회 | `RobotQueryScopeIntegrationTest.appliesSameSqlScopeToListSummaryMapAndDetail` |
| DENY가 Role 및 ALLOW보다 우선 | `AuthenticationUserRepositoryIntegrationTest.calculatesRoleUnionAllowMinusDenyWithExpiryAndActiveState` |
| 만료된 override 무시 | `AuthenticationUserRepositoryIntegrationTest.calculatesRoleUnionAllowMinusDenyWithExpiryAndActiveState` |
| 마지막 ADMIN 제거 및 비활성화 실패 | `AuthenticationUserRepositoryIntegrationTest.rejectsDeactivationRoleRemovalAndDenyForLastActiveAdmin` |
| 마지막 ADMIN 동시 변경 직렬화 | `AuthenticationUserRepositoryIntegrationTest.serializesConcurrentLastAdminDeactivation` |
| 감사 INSERT 실패 시 사용자·역할 변경 롤백 | `AdminManagementIntegrationTest.auditInsertFailureRollsBackApprovalAndRoleAssignment` |
| 감사 로그 조회가 다시 감사 로그에 기록 | `AuditLogIntegrationTest.queriesWithPermissionAndAuditsTheViewWithoutRawFilterValues` |
| MFA 초기화 후 대상의 기존 세션 전부 무효화 | `AdminManagementIntegrationTest.mfaResetRevokesEveryTargetSessionAndWritesAudit` |
| 프론트 메뉴를 우회한 직접 API 요청 거부 | `RobotRegistrationControllerTest.requiresRobotRegisterPermission` |

DB 동작이 핵심인 테스트는 Testcontainers의 실제 PostgreSQL에서 Flyway 마이그레이션을
적용한 뒤 실행한다. Docker를 사용할 수 없어 통합 테스트가 한 건이라도 건너뛰어지면
`securityAcceptanceTest` 자체가 실패하므로, 실행되지 않은 검증을 성공으로 오인하지 않는다.
