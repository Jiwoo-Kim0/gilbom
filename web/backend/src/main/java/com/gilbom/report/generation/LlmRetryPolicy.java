package com.gilbom.report.generation;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;

import org.springframework.stereotype.Component;

import com.gilbom.llm.LlmTransportException;

@Component
public class LlmRetryPolicy {

    private static final int MAXIMUM_RETRIES = 1;
    private static final Duration BASE_DELAY =
            Duration.ofMillis(100);

    public boolean shouldRetry(
            int completedAttemptIndex,
            LlmTransportException failure
    ) {
        return completedAttemptIndex < MAXIMUM_RETRIES
                && failure.retryable();
    }

    public void waitBeforeRetry(int completedAttemptIndex) {
        long multiplier = 1L << Math.min(
                completedAttemptIndex,
                4);
        long jitterMillis =
                ThreadLocalRandom.current().nextLong(51);
        long nanos = BASE_DELAY.multipliedBy(multiplier)
                .plusMillis(jitterMillis)
                .toNanos();
        LockSupport.parkNanos(nanos);
    }
}
