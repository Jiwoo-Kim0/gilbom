package com.gilbom.auth.session;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AuthenticationSessionCleanupJob {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AuthenticationSessionCleanupJob.class);

    private final SessionSecurityStateRepository repository;
    private final AuthenticationSessionProperties properties;
    private final Clock clock;

    public AuthenticationSessionCleanupJob(
            SessionSecurityStateRepository repository,
            AuthenticationSessionProperties properties,
            Clock clock
    ) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(
            initialDelayString = "${gilbom.security.session.cleanup-interval:1m}",
            fixedDelayString = "${gilbom.security.session.cleanup-interval:1m}"
    )
    public void deleteExpiredSessions() {
        try {
            repository.deleteExpiredSecuritySessions(
                    properties.getCleanupBatchSize(),
                    clock.instant()
            );
        } catch (DataAccessException ignored) {
            // DB 오류 원문에 연결 정보나 SQL이 포함될 수 있어 scheduled 로그에 남기지 않는다.
            LOGGER.warn("Expired authentication session cleanup was unavailable");
        }
    }
}
