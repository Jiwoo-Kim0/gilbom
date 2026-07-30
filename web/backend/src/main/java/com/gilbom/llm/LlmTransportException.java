package com.gilbom.llm;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

/**
 * Provider 원문·URL·credential을 보존하지 않는 전송 실패 분류다.
 */
public final class LlmTransportException extends BusinessException {

    private final FailureKind failureKind;

    public LlmTransportException(FailureKind failureKind) {
        super(ErrorCode.LLM_UNAVAILABLE);
        this.failureKind = java.util.Objects.requireNonNull(
                failureKind,
                "failureKind must not be null");
    }

    public FailureKind failureKind() {
        return failureKind;
    }

    public boolean retryable() {
        return failureKind.retryable();
    }

    public enum FailureKind {
        RATE_LIMITED(true),
        PROVIDER_5XX(true),
        NETWORK_TIMEOUT(true),
        PROVIDER_4XX(false),
        CLIENT_FAILURE(false);

        private final boolean retryable;

        FailureKind(boolean retryable) {
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }
}
