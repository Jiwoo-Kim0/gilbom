package com.gilbom.llm;

import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Component;

/**
 * 한 애플리케이션 인스턴스가 동시에 여는 외부 LLM 연결 수를 제한한다.
 */
@Component
public class LlmConcurrencyLimiter {

    private final Semaphore semaphore;

    public LlmConcurrencyLimiter(LlmProperties properties) {
        semaphore = new Semaphore(
                Math.max(
                        1,
                        properties.getMaxConcurrentRequests()),
                true);
    }

    public Optional<Permit> tryAcquire() {
        return semaphore.tryAcquire()
                ? Optional.of(new Permit(semaphore))
                : Optional.empty();
    }

    public static final class Permit implements AutoCloseable {

        private final Semaphore semaphore;
        private final AtomicBoolean released =
                new AtomicBoolean();

        private Permit(Semaphore semaphore) {
            this.semaphore = semaphore;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                semaphore.release();
            }
        }
    }
}
