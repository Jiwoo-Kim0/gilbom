package com.gilbom.auth.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.gilbom.auth.ratelimit.PersistentRateLimitRepository.FailureRateLimitDecision;
import com.gilbom.auth.ratelimit.RateLimitSubjectHasher.HashedRateLimitSubject;

class AuthenticationRateLimiterTest {

    private static final UUID USER_ID =
            UUID.fromString("19000000-0000-4000-8000-000000000001");

    private final AuthenticationRateLimitProperties properties =
            new AuthenticationRateLimitProperties();
    private final RateLimitSubjectHasher subjectHasher =
            Mockito.mock(RateLimitSubjectHasher.class);
    private final PersistentRateLimitRepository repository =
            Mockito.mock(PersistentRateLimitRepository.class);
    private final AuthenticationRateLimiter rateLimiter =
            new AuthenticationRateLimiter(
                    properties,
                    subjectHasher,
                    repository
            );

    @Test
    void hashesNormalizedEmailInsteadOfPersistingTheRawIdentifier() {
        HashedRateLimitSubject subject =
                new HashedRateLimitSubject(new byte[32], 1);
        when(subjectHasher.hash(
                "account-password:user@example.com"
        )).thenReturn(subject);
        when(repository.recordFailure(
                AuthenticationRateLimitType.ACCOUNT_PASSWORD_FAILURE,
                subject,
                5,
                Duration.ofMinutes(15),
                Duration.ofMinutes(15)
        )).thenReturn(new FailureRateLimitDecision(
                5,
                java.time.Instant.parse("2026-07-27T08:15:00Z"),
                900
        ));

        FailureRateLimitDecision result =
                rateLimiter.recordPasswordFailure("User@Example.COM");

        assertThat(result.blocked()).isTrue();
        verify(subjectHasher).hash(
                "account-password:user@example.com"
        );
    }

    @Test
    void separatesMfaFailuresFromPasswordFailures() {
        HashedRateLimitSubject subject =
                new HashedRateLimitSubject(new byte[32], 1);
        when(subjectHasher.hash("account-mfa:" + USER_ID))
                .thenReturn(subject);
        when(repository.inspect(
                AuthenticationRateLimitType.ACCOUNT_MFA_FAILURE,
                subject
        )).thenReturn(new RateLimitDecision(false, 120));

        RateLimitDecision result =
                rateLimiter.inspectMfaFailures(USER_ID);

        assertThat(result.allowed()).isFalse();
        assertThat(result.retryAfterSeconds()).isEqualTo(120);
    }
}
