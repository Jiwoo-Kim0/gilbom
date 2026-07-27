package com.gilbom.auth.ratelimit;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

/**
 * ControllerAdvice가 안정적인 429 본문과 Retry-After를 함께 만들기 위한 예외다.
 */
public final class AuthenticationRateLimitExceededException
        extends BusinessException {

    private final long retryAfterSeconds;

    public AuthenticationRateLimitExceededException(long retryAfterSeconds) {
        super(
                ErrorCode.COMMON_RATE_LIMIT_EXCEEDED,
                "잠시 후 다시 시도해 주세요."
        );
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
