-- 외부 LLM 호출 전 사용자별 일일 요청 수와 전체 일일 credit budget을 원자적으로
-- 예약하고, 원문 prompt·응답 없이 안전한 결과 metadata만 기록한다.

CREATE TABLE request_control.llm_invocation_usage (
    attempt_id UUID NOT NULL,
    usage_date DATE NOT NULL,
    actor_user_id UUID NOT NULL,
    provider VARCHAR(20) NOT NULL,
    model_identifier VARCHAR(100) NOT NULL,
    attempt_status VARCHAR(20) NOT NULL,
    reserved_credits NUMERIC(20,6) NOT NULL,
    accounted_credits NUMERIC(20,6) NOT NULL,
    input_tokens INTEGER,
    output_tokens INTEGER,
    total_tokens INTEGER,
    failure_code VARCHAR(50),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT pk_llm_invocation_usage PRIMARY KEY (attempt_id),
    CONSTRAINT ck_llm_invocation_provider CHECK (
        provider = 'GMS'
    ),
    CONSTRAINT ck_llm_invocation_model CHECK (
        model_identifier IN ('gpt-5.4-mini', 'gpt-5.4')
    ),
    CONSTRAINT ck_llm_invocation_status CHECK (
        attempt_status IN ('STARTED', 'SUCCEEDED', 'FAILED')
    ),
    CONSTRAINT ck_llm_invocation_credits CHECK (
        reserved_credits > 0
        AND accounted_credits >= 0
    ),
    CONSTRAINT ck_llm_invocation_tokens CHECK (
        (input_tokens IS NULL OR input_tokens >= 0)
        AND (output_tokens IS NULL OR output_tokens >= 0)
        AND (total_tokens IS NULL OR total_tokens >= 0)
    ),
    CONSTRAINT ck_llm_invocation_completion CHECK (
        (
            attempt_status = 'STARTED'
            AND completed_at IS NULL
            AND failure_code IS NULL
        )
        OR
        (
            attempt_status = 'SUCCEEDED'
            AND completed_at IS NOT NULL
            AND failure_code IS NULL
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
                'CLIENT_FAILURE',
                'RESPONSE_INVALID'
            )
        )
    ),
    CONSTRAINT ck_llm_invocation_completion_time CHECK (
        completed_at IS NULL OR completed_at >= started_at
    )
);

CREATE INDEX ix_llm_invocation_user_daily
    ON request_control.llm_invocation_usage (
        usage_date,
        actor_user_id,
        started_at
    );

CREATE INDEX ix_llm_invocation_daily_budget
    ON request_control.llm_invocation_usage (
        usage_date,
        model_identifier
    )
    INCLUDE (accounted_credits);

COMMENT ON TABLE request_control.llm_invocation_usage IS
    'LLM 사용자별 일일 호출과 전체 일일 credit hard gate. Prompt·응답·이미지·credential 원문은 저장하지 않는다.';
COMMENT ON COLUMN request_control.llm_invocation_usage.accounted_credits IS
    '호출 전에는 보수적 예약액, 성공 후 usage가 있으면 실제 계산액. 실패·unknown usage는 예약액을 유지한다.';
COMMENT ON COLUMN request_control.llm_invocation_usage.failure_code IS
    'Provider body나 예외 원문이 아닌 고정된 안전 실패 분류';

GRANT SELECT, INSERT, UPDATE
    ON request_control.llm_invocation_usage
    TO "${applicationRole}";
