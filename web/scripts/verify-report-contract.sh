#!/bin/sh

set -eu

OPENAPI=docs/openapi-v1.yaml
AUTHORIZATION=docs/authorization-matrix.md
ERRORS=docs/common-api-response-and-errors.md
CONTRACT=docs/report-status-contract.md
LLM_SCHEMA=docs/report-llm-result.schema.json
ERROR_CODE=backend/src/main/java/com/gilbom/common/error/ErrorCode.java
ERROR_CODE_TEST=backend/src/test/java/com/gilbom/common/error/ErrorCodeTest.java
AUDIT_ACTION=backend/src/main/java/com/gilbom/audit/AuditAction.java
ACCESS_DENIED_RESOLVER=backend/src/main/java/com/gilbom/common/web/AccessDeniedErrorCodeResolver.java
GLOBAL_EXCEPTION_HANDLER=backend/src/main/java/com/gilbom/common/web/GlobalExceptionHandler.java
SECURITY_CONFIG=backend/src/main/java/com/gilbom/security/config/SecurityConfig.java
REPORT_MIGRATION=backend/src/main/resources/db/migration/V30__harden_report_domain_invariants.sql
LLM_QUOTA_MIGRATION=backend/src/main/resources/db/migration/V31__add_llm_invocation_quota_ledger.sql
REPORT_FALLBACK_MIGRATION=backend/src/main/resources/db/migration/V32__persist_report_generation_fallback.sql
CANONICAL_FINGERPRINT=backend/src/main/java/com/gilbom/common/idempotency/CanonicalRequestFingerprint.java
IDEMPOTENCY_STORE=backend/src/main/java/com/gilbom/common/idempotency/IdempotencyRequestStore.java
IDEMPOTENCY_CLEANUP_JOB=backend/src/main/java/com/gilbom/common/idempotency/IdempotencyRequestCleanupJob.java
EXPECTED_VERSION_GUARD=backend/src/main/java/com/gilbom/common/concurrency/ExpectedVersionGuard.java
REPORT_DOMAIN_SERVICE=backend/src/main/java/com/gilbom/report/ReportDomainService.java
REPORT_STATE_SERVICE=backend/src/main/java/com/gilbom/report/ReportStateTransitionService.java
REPORT_CONTROLLER=backend/src/main/java/com/gilbom/report/ReportController.java
REPORT_COMMAND_SERVICE=backend/src/main/java/com/gilbom/report/ReportCommandService.java
REPORT_PDF_SERVICE=backend/src/main/java/com/gilbom/report/pdf/ApprovedReportPdfService.java
REPORT_PDF_STORAGE=backend/src/main/java/com/gilbom/report/pdf/LocalApprovedReportPdfStorage.java
REPORT_QUERY_REPOSITORY=backend/src/main/java/com/gilbom/report/ReportQueryRepository.java
REPORT_INTEGRATION_TEST=backend/src/test/java/com/gilbom/report/integration/ReportDomainServiceIntegrationTest.java
REPORT_API_INTEGRATION_TEST=backend/src/test/java/com/gilbom/report/integration/ReportApplicationServiceIntegrationTest.java
REPORT_CONTROLLER_TEST=backend/src/test/java/com/gilbom/report/ReportControllerTest.java
LLM_IMAGE_CONTENT=backend/src/main/java/com/gilbom/llm/LlmImageContent.java
GMS_LLM_CLIENT=backend/src/main/java/com/gilbom/llm/GmsLlmClient.java
REPORT_IMAGE_READER=backend/src/main/java/com/gilbom/report/generation/DefectImageContentReader.java
REPORT_LLM_REQUEST_FACTORY=backend/src/main/java/com/gilbom/report/generation/ReportLlmRequestFactory.java
REPORT_LLM_INPUT=backend/src/main/java/com/gilbom/report/generation/ReportLlmInput.java
REPORT_LLM_INPUT_PREPARER=backend/src/main/java/com/gilbom/report/generation/ReportLlmInputPreparer.java
LLM_OUTBOUND_GUARD=backend/src/main/java/com/gilbom/llm/LlmOutboundInputGuard.java
REPORT_LLM_RESULT_VALIDATOR=backend/src/main/java/com/gilbom/report/generation/ReportLlmResultValidator.java
REPORT_LLM_PIPELINE=backend/src/main/java/com/gilbom/report/generation/ReportLlmGenerationPipeline.java
REPORT_GENERATION_DATA_SERVICE=backend/src/main/java/com/gilbom/report/generation/ReportGenerationDataService.java
REPORT_GENERATION_DATA_REPOSITORY=backend/src/main/java/com/gilbom/report/generation/ReportGenerationDataRepository.java
AUDIT_SNAPSHOT=backend/src/main/java/com/gilbom/audit/AuditSnapshot.java
FRONTEND_PERMISSIONS=frontend/gilbom/src/features/auth/permissions.ts
FRONTEND_ROUTER=frontend/gilbom/src/app/router.tsx
FRONTEND_NAVIGATION=frontend/gilbom/src/features/navigationbar.tsx

assert_contains() {
    file=$1
    expected=$2
    label=$3

    if ! grep -Fq "$expected" "$file"; then
        echo "Report contract mismatch: ${label} (${file})" >&2
        exit 1
    fi
}

assert_path_contains() {
    path=$1
    expected=$2
    label=$3

    if ! awk -v heading="  ${path}:" -v expected="$expected" '
        {
            line = $0
            sub(/\r$/, "", line)
        }
        line == heading {
            in_path = 1
            next
        }
        in_path && line ~ /^  \/api\/v1\// {
            exit
        }
        in_path && index(line, expected) > 0 {
            found = 1
        }
        END {
            exit(found ? 0 : 1)
        }
    ' "$OPENAPI"; then
        echo "Report OpenAPI mismatch: ${label} (${path})" >&2
        exit 1
    fi
}

assert_path_contains /api/v1/reports \
    "operationId: listReports" \
    "list operation"
assert_path_contains /api/v1/reports \
    "operationId: createReport" \
    "create operation"
assert_path_contains /api/v1/reports \
    "#/components/parameters/IdempotencyKey" \
    "create Idempotency-Key"
assert_path_contains /api/v1/reports \
    "#/components/schemas/ReportListResponse" \
    "list response"
assert_path_contains /api/v1/reports \
    "#/components/schemas/ReportCreateRequest" \
    "manual/template/LLM create request"
assert_path_contains /api/v1/reports \
    "name: createdBy" \
    "author filter"
assert_path_contains /api/v1/reports \
    "name: periodFrom" \
    "period overlap start filter"
assert_path_contains /api/v1/reports \
    "name: periodTo" \
    "period overlap end filter"
assert_path_contains /api/v1/reports \
    "COMMON_CONFLICT" \
    "in-progress idempotency conflict"

assert_path_contains '/api/v1/reports/{reportId}' \
    "operationId: getReport" \
    "detail operation"
assert_path_contains '/api/v1/reports/{reportId}' \
    "operationId: updateReport" \
    "update operation"
assert_path_contains '/api/v1/reports/{reportId}' \
    "operationId: deleteReport" \
    "soft-delete operation"
assert_path_contains '/api/v1/reports/{reportId}' \
    "#/components/parameters/ExpectedReportVersionNo" \
    "soft-delete expected version"

assert_path_contains '/api/v1/reports/{reportId}/versions' \
    "operationId: listReportVersions" \
    "version list operation"
assert_path_contains '/api/v1/reports/{reportId}/versions/{versionNo}' \
    "operationId: getReportVersion" \
    "version detail operation"
assert_path_contains '/api/v1/reports/{reportId}/submit' \
    "operationId: submitReport" \
    "submit operation"
assert_path_contains '/api/v1/reports/{reportId}/approve' \
    "operationId: approveReport" \
    "approve operation"
assert_path_contains '/api/v1/reports/{reportId}/reject' \
    "operationId: rejectReport" \
    "reject operation"

for schema in \
    ReportStatus \
    ReportGenerationMethod \
    ReportCreateRequest \
    ReportUpdateRequest \
    ReportTransitionRequest \
    ReportDecisionRequest \
    ReportRejectionRequest \
    ReportListResponse \
    ReportDetailResponse \
    ReportVersionListResponse \
    ReportVersionResponse \
    ReportEvidenceSnapshot \
    ApprovedPdfInfoResponse \
    ReportStatisticsSnapshot \
    LlmReportResult
do
    assert_contains "$OPENAPI" "    ${schema}:" "${schema} schema"
done

for code in \
    REPORT_STATE_CONFLICT \
    REPORT_ACCESS_DENIED \
    REPORT_VALIDATION_FAILED \
    IDEMPOTENCY_KEY_REUSED \
    LLM_UNAVAILABLE \
    LLM_RESPONSE_INVALID
do
    assert_contains "$OPENAPI" "        - ${code}" "${code} OpenAPI enum"
    assert_contains "$ERRORS" "\`${code}\`" "${code} error documentation"
    assert_contains "$ERROR_CODE" "${code}(" "${code} runtime enum"
done

assert_contains "$ACCESS_DENIED_RESOLVER" "REPORT_ACCESS_DENIED" "report access-denied resolver"
assert_contains "$GLOBAL_EXCEPTION_HANDLER" "AccessDeniedErrorCodeResolver.resolve" "MVC access-denied mapping"
assert_contains "$SECURITY_CONFIG" "AccessDeniedErrorCodeResolver.resolve" "security access-denied mapping"

for field in \
    title \
    summary \
    statistics \
    keyDefects \
    actionRecommendations \
    period \
    promptVersion \
    modelIdentifier
do
    assert_contains "$LLM_SCHEMA" "\"${field}\"" "${field} LLM JSON Schema field"
done

assert_contains "$LLM_SCHEMA" '"additionalProperties": false' \
    "strict LLM JSON Schema"
assert_contains "$CONTRACT" '생성 ───────────────→ DRAFT' \
    "create to DRAFT transition"
assert_contains "$CONTRACT" '| `IN_REVIEW` | 승인 | `APPROVED` |' \
    "approve transition"
assert_contains "$CONTRACT" '| `IN_REVIEW` | 반려 | `REJECTED` |' \
    "reject transition"
assert_contains "$CONTRACT" '## 8. 권한과 UI 버튼 매트릭스' \
    "UI matrix"
assert_contains "$CONTRACT" 'List<ImageContent>' \
    "LLM image list contract"
assert_contains "$CONTRACT" 'Presigned URL, 일반 외부 URL' \
    "LLM URL prohibition"
assert_contains "$CONTRACT" '빈 목록이면 같은 요청을 텍스트만으로 생성한다.' \
    "text-only image fallback"
assert_contains "$AUTHORIZATION" \
    '| GET | `/api/v1/reports/{reportId}/versions/{versionNo}` | `REPORT_VIEW` |' \
    "version detail authorization"

assert_contains "$ERROR_CODE_TEST" \
    "ErrorCode.LLM_RESPONSE_INVALID.httpStatus()" \
    "error status regression test"

for action in \
    REPORT_CREATED \
    REPORT_UPDATED \
    REPORT_SUBMITTED \
    REPORT_APPROVED \
    REPORT_REJECTED \
    REPORT_DELETED
do
    assert_contains "$AUDIT_ACTION" "${action}(\"${action}\")" \
        "${action} audit action"
done

for field in \
    generation_method \
    evidence_snapshot \
    approved_pdf_version_no \
    approved_pdf_storage_key \
    approved_pdf_sha256 \
    approved_pdf_created_at \
    resource_type \
    resource_id
do
    assert_contains "$REPORT_MIGRATION" "$field" \
        "${field} report persistence field"
done

assert_contains "$REPORT_MIGRATION" \
    "ix_idempotency_report_expiry" \
    "report idempotency expiry index"
assert_contains "$REPORT_MIGRATION" \
    "tr_anomaly_report_lifecycle" \
    "database report lifecycle guard"
assert_contains "$REPORT_MIGRATION" \
    "tr_report_version_current_pointer" \
    "database current-version reverse pointer"
assert_contains "$CANONICAL_FINGERPRINT" \
    "fields.sort(Comparator.naturalOrder())" \
    "canonical request object ordering"
assert_contains "$IDEMPOTENCY_STORE" \
    "NOW() + INTERVAL '24 hours'" \
    "24-hour idempotency retention"
assert_contains "$IDEMPOTENCY_STORE" \
    "Propagation.MANDATORY" \
    "idempotency transaction requirement"
assert_contains "$IDEMPOTENCY_STORE" \
    "FOR UPDATE SKIP LOCKED" \
    "bounded idempotency cleanup locking"
assert_contains "$IDEMPOTENCY_CLEANUP_JOB" \
    "CLEANUP_BATCH_SIZE = 1_000" \
    "idempotency cleanup batch size"
assert_contains "$EXPECTED_VERSION_GUARD" \
    "expectedVersionNo != currentVersionNo" \
    "expected version comparison"
assert_contains "$REPORT_STATE_SERVICE" \
    "ReportAction.APPROVE, ReportStatus.APPROVED" \
    "centralized approve transition"
assert_contains "$REPORT_DOMAIN_SERVICE" \
    "auditLogService.appendRequired" \
    "transactional required report audit"
assert_contains "$REPORT_DOMAIN_SERVICE" \
    "createClaimed" \
    "claim-first generated report orchestration"
assert_contains "$REPORT_CONTROLLER" \
    "@PreAuthorize(\"hasAuthority('REPORT_VIEW')\")" \
    "report API view permission"
assert_contains "$REPORT_CONTROLLER" \
    "@RequestHeader(\"Idempotency-Key\")" \
    "report create idempotency header"
for transition in submit approve reject
do
    assert_contains "$REPORT_CONTROLLER" \
        "@PostMapping(\"/{reportId}/${transition}\")" \
        "${transition} controller endpoint"
    assert_contains "$REPORT_COMMAND_SERVICE" \
        "domainService.${transition}" \
        "${transition} command boundary"
done
assert_contains "$REPORT_CONTROLLER" \
    "@PreAuthorize(\"hasAuthority('REPORT_APPROVE')\")" \
    "approve/reject API permission"
assert_contains "$REPORT_COMMAND_SERVICE" \
    "domainService.softDelete" \
    "report soft-delete command boundary"
assert_contains "$REPORT_DOMAIN_SERVICE" \
    "approvedPdfService.create" \
    "approved PDF generation before approval commit"
assert_contains "$REPORT_DOMAIN_SERVICE" \
    "repository.bindApprovedPdf" \
    "approved PDF version binding"
assert_contains "$REPORT_PDF_SERVICE" \
    "registerRollbackCleanup" \
    "approved PDF rollback cleanup"
assert_contains "$REPORT_PDF_STORAGE" \
    "Files.move(temporary, target)" \
    "approved PDF create-only storage"
assert_contains "$REPORT_QUERY_REPOSITORY" \
    "report.deleted_at IS NULL" \
    "soft-deleted report query exclusion"
assert_contains "$REPORT_QUERY_REPOSITORY" \
    "report.created_by = :createdBy" \
    "report author filter"
assert_contains "$REPORT_QUERY_REPOSITORY" \
    "report.period_to >= :periodFrom" \
    "report overlap period filter"
assert_contains "$REPORT_INTEGRATION_TEST" \
    "rollsBackHeaderVersionIdempotencyAndAuditAsOneTransaction" \
    "report transaction atomicity test"
assert_contains "$REPORT_INTEGRATION_TEST" \
    "concurrentDuplicateCreatesReturnOneCreationAndOneReplay" \
    "concurrent idempotent create test"
assert_contains "$REPORT_INTEGRATION_TEST" \
    "concurrentUpdatesWithOneExpectedVersionAllowOnlyOneWinner" \
    "concurrent report update test"
assert_contains "$REPORT_INTEGRATION_TEST" \
    "concurrentSubmitAndDecisionTransitionsAllowOnlyFirstCommit" \
    "concurrent report transition test"
assert_contains "$REPORT_API_INTEGRATION_TEST" \
    "listsWithAllFiltersAndStablePagination" \
    "report list/filter integration test"
assert_contains "$REPORT_API_INTEGRATION_TEST" \
    "updateAppendsVersionAndPreservesOldSnapshot" \
    "report append-only update integration test"
assert_contains "$REPORT_API_INTEGRATION_TEST" \
    "rejectedEditReturnsToDraftAndSoftDeleteKeepsHistory" \
    "report rejected edit and soft-delete integration test"
assert_contains "$REPORT_API_INTEGRATION_TEST" \
    "serviceBoundaryUsesCurrentDatabasePermissions" \
    "report service permission integration test"
assert_contains "$REPORT_API_INTEGRATION_TEST" \
    "reviewCommandsEnforcePermissionAndBindImmutablePdf" \
    "report review command and PDF integration test"
assert_contains "$REPORT_API_INTEGRATION_TEST" \
    "rejectionRequiresReasonAndDoesNotCreateVersion" \
    "report rejection no-version integration test"
assert_contains "$REPORT_API_INTEGRATION_TEST" \
    "llmFallbackCreatesOneDraftAndReplaySkipsPipeline" \
    "LLM fallback and replay integration test"
assert_contains "$REPORT_CONTROLLER_TEST" \
    "requiresReportViewPermissionAtApiBoundary" \
    "report controller permission test"
assert_contains "$REPORT_CONTROLLER_TEST" \
    "approvesAndRejectsOnlyWithApprovePermission" \
    "report decision controller permission test"
assert_contains "$REPORT_IMAGE_READER" \
    "MAX_IMAGES = 3" \
    "image reader expansion cap"
assert_contains "$REPORT_LLM_REQUEST_FACTORY" \
    "CURRENT_MAX_IMAGES = 1" \
    "current one-image behavior"
assert_contains "$REPORT_LLM_INPUT" \
    "MAXIMUM_EVIDENCE_ITEMS = 20" \
    "typed LLM evidence allowlist"
assert_contains "$REPORT_LLM_INPUT_PREPARER" \
    "outboundGuard.validate" \
    "LLM input preflight guard"
assert_contains "$LLM_OUTBOUND_GUARD" \
    "MAXIMUM_JSON_BYTES = 128 * 1024" \
    "LLM input payload cap"
assert_contains "$REPORT_LLM_RESULT_VALIDATOR" \
    "validateSchemaShape" \
    "strict LLM result shape validation"
assert_contains "$REPORT_LLM_RESULT_VALIDATOR" \
    "validateStatistics" \
    "LLM source statistics validation"
assert_contains "$REPORT_LLM_RESULT_VALIDATOR" \
    "validateEvidence" \
    "LLM source evidence validation"
assert_contains "$REPORT_LLM_PIPELINE" \
    "retryPolicy.shouldRetry" \
    "bounded conditional LLM retry"
assert_contains "$REPORT_LLM_PIPELINE" \
    "TemplateReportGenerator" \
    "safe template fallback"
assert_contains "$REPORT_GENERATION_DATA_SERVICE" \
    "ReportGenerationCriteria.from" \
    "server-owned report generation filters"
assert_contains "$REPORT_GENERATION_DATA_REPOSITORY" \
    "WITH visible_missions AS" \
    "single report generation SQL snapshot"
assert_contains "$LLM_QUOTA_MIGRATION" \
    "llm_invocation_usage" \
    "persistent LLM quota ledger"
assert_contains "$LLM_QUOTA_MIGRATION" \
    "Prompt·응답·이미지·credential 원문은 저장하지 않는다" \
    "LLM quota payload prohibition"
assert_contains "$REPORT_FALLBACK_MIGRATION" \
    "generation_fallback_reason" \
    "safe report generation fallback persistence"
assert_contains "$GMS_LLM_CLIENT" \
    "Base64.getEncoder().encodeToString" \
    "in-memory image Base64 conversion"
assert_contains "$LLM_IMAGE_CONTENT" \
    "containsExif(bytes)" \
    "EXIF rejection"
assert_contains "$AUDIT_SNAPSHOT" \
    '"imagebase64"' \
    "image Base64 audit redaction"

for permission in \
    REPORT_VIEW \
    REPORT_CREATE \
    REPORT_UPDATE \
    REPORT_APPROVE
do
    assert_contains "$FRONTEND_PERMISSIONS" \
        "export const ${permission} = '${permission}'" \
        "${permission} frontend permission"
done

assert_contains "$FRONTEND_ROUTER" \
    "<RequirePermission permission={REPORT_VIEW}>" \
    "report route permission"
assert_contains "$FRONTEND_NAVIGATION" \
    "requiredPermission: REPORT_VIEW" \
    "report navigation permission"

echo "Report API contract synchronization: PASS"
