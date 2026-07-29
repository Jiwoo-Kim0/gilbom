#!/bin/sh

set -eu

OPENAPI=docs/openapi-v1.yaml
AUTHORIZATION=docs/authorization-matrix.md
ERRORS=docs/common-api-response-and-errors.md
CONTRACT=docs/statistics-api-contract.md
ERROR_CODE=backend/src/main/java/com/gilbom/common/error/ErrorCode.java
ERROR_CODE_TEST=backend/src/test/java/com/gilbom/common/error/ErrorCodeTest.java

assert_contains() {
    file=$1
    expected=$2
    label=$3

    if ! grep -Fq "$expected" "$file"; then
        echo "Statistics contract mismatch: ${label} (${file})" >&2
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
        echo "Statistics OpenAPI mismatch: ${label} (${path})" >&2
        exit 1
    fi
}

assert_path_contains /api/v1/statistics/by-status \
    "operationId: getStatisticsByDefectStatus" \
    "by-status operation"
assert_path_contains /api/v1/statistics/by-status \
    "x-required-permission: DEFECT_STATUS_VIEW" \
    "by-status permission"
assert_path_contains /api/v1/statistics/by-status \
    "#/components/schemas/StatisticsByStatusResponse" \
    "by-status response DTO"
assert_path_contains /api/v1/statistics/by-status \
    "#/components/responses/StatisticsPeriodInvalid" \
    "by-status semantic period error"

assert_path_contains /api/v1/statistics/by-defect-type \
    "operationId: getStatisticsByDefectType" \
    "by-defect-type operation"
assert_path_contains /api/v1/statistics/by-defect-type \
    "x-required-permission: DEFECT_STATUS_VIEW" \
    "by-defect-type permission"
assert_path_contains /api/v1/statistics/by-defect-type \
    "#/components/schemas/StatisticsByDefectTypeResponse" \
    "by-defect-type response DTO"
assert_path_contains /api/v1/statistics/by-defect-type \
    "#/components/responses/StatisticsPeriodInvalid" \
    "by-defect-type semantic period error"

assert_path_contains /api/v1/statistics/by-area \
    "operationId: getStatisticsByArea" \
    "by-area operation"
assert_path_contains /api/v1/statistics/by-area \
    "x-required-permissions: [MISSION_VIEW, DEFECT_STATUS_VIEW]" \
    "by-area ALL permissions"
assert_path_contains /api/v1/statistics/by-area \
    "#/components/parameters/StatisticsPatrolAreaId" \
    "by-area patrolAreaId"
assert_path_contains /api/v1/statistics/by-area \
    "#/components/parameters/Page" \
    "by-area page"
assert_path_contains /api/v1/statistics/by-area \
    "#/components/parameters/Size" \
    "by-area size"
assert_path_contains /api/v1/statistics/by-area \
    "#/components/schemas/StatisticsByAreaResponse" \
    "by-area response DTO"
assert_path_contains /api/v1/statistics/by-area \
    "#/components/responses/StatisticsPatrolAreaNotFound" \
    "by-area missing patrol area"

assert_path_contains /api/v1/statistics/by-period \
    "operationId: getStatisticsByPeriod" \
    "by-period operation"
assert_path_contains /api/v1/statistics/by-period \
    "x-required-permissions: [MISSION_VIEW, DEFECT_STATUS_VIEW]" \
    "by-period ALL permissions"
assert_path_contains /api/v1/statistics/by-period \
    "#/components/parameters/StatisticsGranularity" \
    "by-period granularity"
assert_path_contains /api/v1/statistics/by-period \
    "#/components/schemas/StatisticsByPeriodResponse" \
    "by-period response DTO"

for path in \
    /api/v1/statistics/by-status \
    /api/v1/statistics/by-defect-type \
    /api/v1/statistics/by-area \
    /api/v1/statistics/by-period
do
    assert_path_contains "$path" \
        "#/components/parameters/StatisticsFrom" \
        "${path} required from"
    assert_path_contains "$path" \
        "#/components/parameters/StatisticsTo" \
        "${path} required to"
    assert_path_contains "$path" \
        "#/components/responses/StatisticsBadRequest" \
        "${path} 400 parameter response"
    assert_path_contains "$path" \
        "#/components/responses/StatisticsPeriodInvalid" \
        "${path} 422 period response"
done

for schema in \
    StatisticsByStatusResponse \
    StatisticsByDefectTypeResponse \
    StatisticsAggregateMetrics \
    StatisticsByAreaResponse \
    StatisticsByPeriodResponse
do
    assert_contains "$OPENAPI" "    ${schema}:" "${schema} schema"
done

assert_contains "$OPENAPI" "    StatisticsFrom:" "required from parameter"
assert_contains "$OPENAPI" "    StatisticsTo:" "required to parameter"
assert_contains "$OPENAPI" "    StatisticsBadRequest:" "statistics parameter error response"
assert_contains "$OPENAPI" "    StatisticsPeriodInvalid:" "period error response"
assert_contains "$OPENAPI" "        - STATISTICS_PERIOD_INVALID" "OpenAPI error enum"
assert_contains "$AUTHORIZATION" \
    '| GET | `/api/v1/statistics/by-area` | `MISSION_VIEW` **AND** `DEFECT_STATUS_VIEW` |' \
    "authorization matrix AND rule"
assert_contains "$ERRORS" \
    '| `STATISTICS_PERIOD_INVALID` | 422 |' \
    "error documentation"
assert_contains "$CONTRACT" \
    '반열림 구간 `[from,to)`' \
    "half-open UTC window"
assert_contains "$CONTRACT" \
    '`Duration.ofDays(366)` 이하' \
    "maximum statistics period"
assert_contains "$ERROR_CODE" \
    "STATISTICS_PERIOD_INVALID(HttpStatus.UNPROCESSABLE_ENTITY" \
    "runtime ErrorCode"
assert_contains "$ERROR_CODE_TEST" \
    "ErrorCode.STATISTICS_PERIOD_INVALID.httpStatus()" \
    "ErrorCode status test"

echo "Statistics API contract synchronization: PASS"
