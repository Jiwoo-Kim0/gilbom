package com.gilbom.auth.ratelimit;

public record RateLimitDecision(
        boolean allowed,
        long retryAfterSeconds,
        boolean firstRejectedRequest
) {

    public RateLimitDecision {
        retryAfterSeconds = Math.max(0, retryAfterSeconds);
        if (allowed && firstRejectedRequest) {
            throw new IllegalArgumentException(
                    "An allowed request cannot be the first rejection");
        }
    }

    public RateLimitDecision(
            boolean allowed,
            long retryAfterSeconds
    ) {
        this(allowed, retryAfterSeconds, false);
    }
}
