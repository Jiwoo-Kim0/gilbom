-- LLM 원문이나 내부 예외 대신 고정된 template fallback 분류만 버전에 남긴다.
ALTER TABLE report.report_version
    ADD COLUMN generation_fallback_reason VARCHAR(50),
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
                'LLM_UNAVAILABLE',
                'LLM_RESPONSE_INVALID',
                'QUOTA_GUARD_UNAVAILABLE'
            )
        )
    );

COMMENT ON COLUMN report.report_version.generation_fallback_reason IS
    'LLM 생성이 template으로 낮아진 안전한 고정 사유. Prompt·응답·예외 원문은 저장하지 않는다.';
