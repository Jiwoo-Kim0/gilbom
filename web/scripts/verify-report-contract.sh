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
    "create request"
assert_path_contains /api/v1/reports \
    "LLM_RESPONSE_INVALID" \
    "LLM invalid response code"

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
