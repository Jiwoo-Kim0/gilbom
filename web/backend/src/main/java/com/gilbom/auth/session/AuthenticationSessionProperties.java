package com.gilbom.auth.session;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Component
@ConfigurationProperties(prefix = "gilbom.security.session")
public class AuthenticationSessionProperties {

    private Duration idleTimeout = Duration.ofMinutes(30);
    private Duration absoluteTimeout = Duration.ofHours(8);
    private Duration challengeTimeout = Duration.ofMinutes(5);
    private int maximumAuthenticatedSessions = 1;
    private int maximumChallengeFailures = 5;
    private Duration cleanupInterval = Duration.ofMinutes(1);
    private int cleanupBatchSize = 1_000;

    @PostConstruct
    void validate() {
        requirePositiveWholeSeconds(idleTimeout, "idle timeout");
        requirePositiveWholeSeconds(absoluteTimeout, "absolute timeout");
        requirePositiveWholeSeconds(challengeTimeout, "challenge timeout");
        requirePositiveWholeSeconds(cleanupInterval, "cleanup interval");

        if (!idleTimeout.equals(Duration.ofMinutes(30))) {
            throw new IllegalStateException(
                    "Session idle timeout must be exactly 30 minutes");
        }
        if (!absoluteTimeout.equals(Duration.ofHours(8))) {
            throw new IllegalStateException(
                    "Session absolute timeout must be exactly 8 hours");
        }
        if (!challengeTimeout.equals(Duration.ofMinutes(5))) {
            throw new IllegalStateException(
                    "Session challenge timeout must be exactly 5 minutes");
        }
        if (maximumAuthenticatedSessions != 1) {
            throw new IllegalStateException(
                    "Exactly one authenticated session per user is required");
        }
        if (maximumChallengeFailures != 5) {
            throw new IllegalStateException(
                    "Exactly five challenge attempts are required");
        }
        if (cleanupBatchSize <= 0) {
            throw new IllegalStateException(
                    "Session cleanup batch size must be positive");
        }
    }

    public int idleTimeoutSeconds() {
        return Math.toIntExact(idleTimeout.getSeconds());
    }

    private static void requirePositiveWholeSeconds(
            Duration value,
            String propertyName
    ) {
        if (value == null
                || value.isZero()
                || value.isNegative()
                || value.getNano() != 0
                || value.getSeconds() > Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "Session " + propertyName
                            + " must be a positive whole-second duration");
        }
    }

    public Duration getIdleTimeout() {
        return idleTimeout;
    }

    public void setIdleTimeout(Duration idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    public Duration getAbsoluteTimeout() {
        return absoluteTimeout;
    }

    public void setAbsoluteTimeout(Duration absoluteTimeout) {
        this.absoluteTimeout = absoluteTimeout;
    }

    public Duration getChallengeTimeout() {
        return challengeTimeout;
    }

    public void setChallengeTimeout(Duration challengeTimeout) {
        this.challengeTimeout = challengeTimeout;
    }

    public int getMaximumAuthenticatedSessions() {
        return maximumAuthenticatedSessions;
    }

    public void setMaximumAuthenticatedSessions(int value) {
        this.maximumAuthenticatedSessions = value;
    }

    public int getMaximumChallengeFailures() {
        return maximumChallengeFailures;
    }

    public void setMaximumChallengeFailures(int value) {
        this.maximumChallengeFailures = value;
    }

    public Duration getCleanupInterval() {
        return cleanupInterval;
    }

    public void setCleanupInterval(Duration cleanupInterval) {
        this.cleanupInterval = cleanupInterval;
    }

    public int getCleanupBatchSize() {
        return cleanupBatchSize;
    }

    public void setCleanupBatchSize(int cleanupBatchSize) {
        this.cleanupBatchSize = cleanupBatchSize;
    }
}
