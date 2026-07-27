package com.gilbom.auth.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RateLimitCleanupJob {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(RateLimitCleanupJob.class);

    private final PublicAuthRateLimitProperties properties;
    private final PersistentRateLimitRepository repository;

    public RateLimitCleanupJob(
            PublicAuthRateLimitProperties properties,
            PersistentRateLimitRepository repository
    ) {
        this.properties = properties;
        this.repository = repository;
    }

    @Scheduled(
            initialDelayString = "${gilbom.security.rate-limit.public-auth.cleanup-interval:10m}",
            fixedDelayString = "${gilbom.security.rate-limit.public-auth.cleanup-interval:10m}"
    )
    public void deleteExpiredBuckets() {
        try {
            repository.deleteExpiredBatch(properties.getCleanupBatchSize());
        } catch (DataAccessException exception) {
            // SQL·bind 값·DB 예외 원문을 scheduled task 로그에 남기지 않는다.
            LOGGER.warn("Expired rate-limit bucket cleanup was unavailable");
        }
    }
}
