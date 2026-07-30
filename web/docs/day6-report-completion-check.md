# Day 6 보고서 통합 검증과 완료 판정

기준일: 2026-07-30

## 1. 판정 원칙

- 외부 GMS를 실제 호출하지 않는다. 통합 검증에서는 Fixture snapshot과
  `FakeLlmClient`·Mock provider 응답을 사용해 비용, 개인정보 외부 전송, 외부 장애에
  영향을 받지 않게 한다.
- PostgreSQL 동작이 필요한 시나리오는 Docker socket을 연결한 실제 PostGIS
  Testcontainers와 Flyway 전체 migration에서 실행한다.
- `skipped=0`이 아니면 통과로 판정하지 않는다. Docker 없는 환경에서
  `@Testcontainers(disabledWithoutDocker = true)`가 조용히 건너뛰는 것을 성공으로
  오인하지 않는다.
- SSE와 마찬가지로 프론트 표시의 정본은 REST 응답이다. 악성 문자열 검증은
  React text-node 렌더링 결과를 함께 검사한다.

## 2. 필수 시나리오 추적표

| ID | 필수 시나리오 | 자동 검증 근거 | 합격 조건 |
|---|---|---|---|
| S01 | 통계 → LLM 생성 → DRAFT | `ReportGenerationDataRepositoryIntegrationTest.loadsOneScopedInternallyConsistentSnapshot`, `ReportLlmGenerationPipelineTest.returnsValidatedLlmContentWithoutFallback`, `ReportApplicationServiceIntegrationTest.validLlmOutcomePersistsDraftModelAndPromptVersion` | 실제 DB 통계 snapshot과 검증된 LLM Fixture 결과가 `LLM`, version 1, `DRAFT`로 저장 |
| S02 | 통계 → LLM 실패 → 템플릿 → DRAFT | `ReportLlmGenerationPipelineTest.invalidSchemaIsNotRetriedAndFallsBackToTemplate`, `ReportApplicationServiceIntegrationTest.llmFallbackCreatesOneDraftAndReplaySkipsPipeline` | provider·Schema 실패가 `TEMPLATE`, 안전한 fallback code, `DRAFT`로 저장 |
| S03 | DRAFT 수정 → 새 버전 | `ReportApplicationServiceIntegrationTest.updateAppendsVersionAndPreservesOldSnapshot` | 기존 version 보존, current version 증가 |
| S04 | DRAFT → submit → IN_REVIEW | `ReportApplicationServiceIntegrationTest.reviewCommandsEnforcePermissionAndBindImmutablePdf` | expected version 검사 후 `IN_REVIEW`와 감사 로그 저장 |
| S05 | IN_REVIEW → approve → APPROVED | `ReportApplicationServiceIntegrationTest.reviewCommandsEnforcePermissionAndBindImmutablePdf` | 승인자·시각·version·PDF와 `APPROVED` 저장 |
| S06 | IN_REVIEW → reject → REJECTED | `ReportApplicationServiceIntegrationTest.rejectionRequiresReasonAndDoesNotCreateVersion` | 사유 저장, 본문 version 추가 없이 `REJECTED` |
| S07 | REJECTED 수정 → 새 버전 → DRAFT | `ReportApplicationServiceIntegrationTest.rejectedEditReturnsToDraftAndSoftDeleteKeepsHistory` | version 증가, 반려 사유 보존, `DRAFT` |
| S08 | 승인 PDF와 승인 버전 일치 | `ReportApplicationServiceIntegrationTest.detailReturnsApprovalAndImmutablePdfIdentity`, `ApprovedReportPdfTest.localStorageReusesSameBytesAndRejectsReplacement` | 승인 version·PDF version·hash 일치, 교체 거부 |
| S09 | 최신 version 작성자 자기 승인 거부 | `ReportDomainServiceIntegrationTest.blocksReviewAndApprovedMutationAndBindsPdfToApprovedVersion` | `REPORT_STATE_CONFLICT`, 승인 정보 미생성 |
| S10 | 권한 없는 수정·승인·반려 거부 | `ReportApplicationServiceIntegrationTest.serviceBoundaryUsesCurrentDatabasePermissions`, `ReportControllerTest.approvesAndRejectsOnlyWithApprovePermission` | 현재 DB 권한 기준 `REPORT_ACCESS_DENIED` |
| S11 | 중복 생성 요청 방지 | `ReportDomainServiceIntegrationTest.concurrentDuplicateCreatesReturnOneCreationAndOneReplay` | 보고서·version·감사 각각 한 건 |
| S12 | 동일 멱등 key·다른 body 거부 | `ReportDomainServiceIntegrationTest.createsOnceAndReplaysTheStoredResponseForTwentyFourHours` | `IDEMPOTENCY_KEY_REUSED` |
| S13 | expectedVersionNo 충돌 | `ReportDomainServiceIntegrationTest.concurrentUpdatesWithOneExpectedVersionAllowOnlyOneWinner`, `ReportControllerTest.mapsVersionConflictWithoutReportContent` | 한 요청만 성공, 409에 본문·현재 version 미노출 |
| S14 | 악성 HTML·script 렌더링 | `safeReportText.test.ts` | script, img, svg, link element를 만들지 않고 text로 escape |
| S15 | 개인정보·Secret 전송 차단 | `ReportLlmInputPreparerTest`, `GmsLlmClientTest.sendsOnlyServerControlledModelPromptAndEndpoint` | outbound allowlist 외 필드와 Secret pattern을 provider 호출 전에 차단 |
| S16 | Prompt·응답 원문 로그 미기록 | `ReportApplicationServiceIntegrationTest.validLlmOutcomePersistsDraftModelAndPromptVersion`, `LlmInvocationQuotaRepositoryIntegrationTest.storesOnlySafeUsageMetadataOnCompletion`, `GmsLlmClientTest.providerFailureDoesNotRetainSecretOrRawResponse` | 감사·quota에는 metadata와 안전 code만 저장하고 원문·provider body·API key 미보존 |
| S17 | Rate Limit·Budget 초과 | `ReportLlmGenerationPipelineTest.rateAndBudgetDenialsNeverCallTheProvider`, `LlmInvocationQuotaRepositoryIntegrationTest.enforcesPerUserDailyLimitAndGlobalBudget` | 외부 호출 없이 안전한 template fallback |
| S18 | LLM 정책 미승인 시 외부 호출 차단 | `LlmPolicyGateTest`, `ReportLlmGenerationPipelineTest.disabledPolicyAndConcurrencyLimitUseTemplateWithoutQuota` | Disabled client 경로, quota·provider 미호출 |
| S19 | soft-delete 상태·권한 검증 | `ReportApplicationServiceIntegrationTest.rejectedEditReturnsToDraftAndSoftDeleteKeepsHistory`, `ReportDomainServiceIntegrationTest.blocksReviewAndApprovedMutationAndBindsPdfToApprovedVersion`, `ReportApplicationServiceIntegrationTest.serviceBoundaryUsesCurrentDatabasePermissions` | DRAFT·REJECTED와 UPDATE 권한에서만 삭제, 목록 제외·history 유지 |
| S20 | 승인 보고서 수정·삭제 거부 | `ReportDomainServiceIntegrationTest.blocksReviewAndApprovedMutationAndBindsPdfToApprovedVersion` | update·delete 모두 `REPORT_STATE_CONFLICT`, 승인 PDF 불변 |

## 3. 단일 완료 게이트

저장소 root에서 다음 명령을 실행한다.

```sh
sh scripts/report-acceptance-test.sh
```

이 명령은 다음을 모두 만족해야 성공한다.

1. `reportAcceptanceTest`: 보고서·LLM Backend 테스트, Testcontainers 포함,
   `failures=0`, `skipped=0`
2. `verify-report-contract.sh`: OpenAPI·권한·DTO·상태 계약 동기화
3. Frontend Node 테스트: 멱등 key·expected version·버튼 정책·안전 렌더링·SSE 복구
4. TypeScript와 Vite production build

하나라도 실패하거나 건너뛰면 Day 6 보고서 완료로 판정하지 않는다.

## 4. 2026-07-30 실행 결과

| 게이트 | 결과 |
|---|---|
| 보고서·LLM 전용 Backend acceptance | 110 tests, failures 0, skipped 0 |
| Backend 전체 회귀 | 710 tests, failures 0, skipped 0 |
| 보고서 계약 동기화 | PASS |
| Frontend | 15 tests, pass 15, fail 0 |
| TypeScript·Vite production build | PASS |

위 결과에 따라 이 문서의 S01~S20은 모두 충족으로 판정한다.
