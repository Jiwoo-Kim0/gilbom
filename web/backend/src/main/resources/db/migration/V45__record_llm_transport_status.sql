-- Provider 응답 본문은 저장하지 않고, 장애 분류에 필요한 HTTP 상태 코드만 보존한다.
-- 최종 요청 크기 상한을 넘긴 로컬 차단도 별도 고정 코드로 기록한다.

ALTER TABLE request_control.llm_invocation_usage
    ADD COLUMN provider_http_status SMALLINT,
    DROP CONSTRAINT ck_llm_invocation_completion,
    ADD CONSTRAINT ck_llm_invocation_provider_http_status CHECK (
        provider_http_status IS NULL
        OR (
            failure_code = 'RATE_LIMITED'
            AND provider_http_status = 429
        )
        OR (
            failure_code = 'PROVIDER_5XX'
            AND provider_http_status BETWEEN 500 AND 599
        )
        OR (
            failure_code = 'PROVIDER_4XX'
            AND provider_http_status BETWEEN 400 AND 499
            AND provider_http_status <> 429
        )
    ),
    ADD CONSTRAINT ck_llm_invocation_completion CHECK (
        (
            attempt_status = 'STARTED'
            AND completed_at IS NULL
            AND failure_code IS NULL
            AND provider_http_status IS NULL
        )
        OR
        (
            attempt_status = 'SUCCEEDED'
            AND completed_at IS NOT NULL
            AND failure_code IS NULL
            AND provider_http_status IS NULL
        )
        OR
        (
            attempt_status = 'FAILED'
            AND completed_at IS NOT NULL
            AND failure_code IN (
                'RATE_LIMITED',
                'PROVIDER_5XX',
                'NETWORK_TIMEOUT',
                'PROVIDER_4XX',
                'REQUEST_TOO_LARGE',
                'CLIENT_FAILURE',
                'RESPONSE_INVALID'
            )
        )
    );

COMMENT ON COLUMN request_control.llm_invocation_usage.provider_http_status IS
    'Provider 원문 없이 저장하는 400-599 HTTP 상태. Provider 응답이 없는 실패는 NULL';

ALTER TABLE report.report_version
    DROP CONSTRAINT ck_report_version_generation_fallback,
    ADD CONSTRAINT ck_report_version_generation_fallback CHECK (
        generation_fallback_reason IS NULL
        OR (
            generation_method = 'TEMPLATE'
            AND generation_fallback_reason IN (
                'POLICY_DISABLED',
                'USER_DAILY_RATE_LIMIT',
                'DAILY_BUDGET_EXCEEDED',
                'CONCURRENCY_LIMIT',
                'IMAGE_EVIDENCE_INVALID',
                'LLM_REQUEST_TOO_LARGE',
                'LLM_UNAVAILABLE',
                'LLM_RESPONSE_INVALID',
                'QUOTA_GUARD_UNAVAILABLE'
            )
        )
    );
