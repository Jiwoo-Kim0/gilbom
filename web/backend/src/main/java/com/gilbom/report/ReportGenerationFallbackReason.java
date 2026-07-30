package com.gilbom.report;

/**
 * Provider 원문이나 내부 예외 메시지를 포함하지 않는 저장 가능한 fallback 분류.
 */
public enum ReportGenerationFallbackReason {
    POLICY_DISABLED,
    USER_DAILY_RATE_LIMIT,
    DAILY_BUDGET_EXCEEDED,
    CONCURRENCY_LIMIT,
    IMAGE_EVIDENCE_INVALID,
    LLM_UNAVAILABLE,
    LLM_RESPONSE_INVALID,
    QUOTA_GUARD_UNAVAILABLE
}
