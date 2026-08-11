package com.gilbom.llm;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

/**
 * Provider 원문·URL·credential을 보존하지 않는 전송 실패 분류다.
 */
public final class LlmTransportException extends BusinessException {

    private final FailureKind failureKind;
    private final Integer providerHttpStatus;

    public LlmTransportException(FailureKind failureKind) {
        this(failureKind, null);
    }

    public LlmTransportException(
            FailureKind failureKind,
            Integer providerHttpStatus
    ) {
        super(ErrorCode.LLM_UNAVAILABLE);
        this.failureKind = java.util.Objects.requireNonNull(
                failureKind,
                "failureKind must not be null");
        if (providerHttpStatus != null
                && (providerHttpStatus < 400
                        || providerHttpStatus > 599)) {
            throw new IllegalArgumentException(
                    "Provider HTTP status must be an error status");
        }
        this.providerHttpStatus = providerHttpStatus;
    }

    public FailureKind failureKind() {
        return failureKind;
    }

    public boolean retryable() {
        return failureKind.retryable();
    }

    public Integer providerHttpStatus() {
        return providerHttpStatus;
    }

    public boolean imagePayloadRejected() {
        return providerHttpStatus != null
                && providerHttpStatus == 413;
    }

    public enum FailureKind {
        RATE_LIMITED(true),
        PROVIDER_5XX(true),
        NETWORK_TIMEOUT(true),
        PROVIDER_4XX(false),
        REQUEST_TOO_LARGE(false),
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
