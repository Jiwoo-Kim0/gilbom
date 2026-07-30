package com.gilbom.auth.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.gilbom.auth.ratelimit.RateLimitSubjectHasher.HashedRateLimitSubject;

class PublicAuthRateLimiterTest {

    private final PublicAuthRateLimitProperties properties =
            new PublicAuthRateLimitProperties();
    private final RateLimitSubjectHasher subjectHasher =
            Mockito.mock(RateLimitSubjectHasher.class);
    private final PersistentRateLimitRepository repository =
            Mockito.mock(PersistentRateLimitRepository.class);
    private final PublicAuthRateLimiter rateLimiter =
            new PublicAuthRateLimiter(properties, subjectHasher, repository);

    @Test
    void appliesSeparateCsrfAvailabilityAndRegistrationPolicies() {
        HashedRateLimitSubject subject =
                new HashedRateLimitSubject(new byte[32], 1);
        when(subjectHasher.hash("ip:203.0.113.10")).thenReturn(subject);
        when(repository.consume(
                PublicAuthRateLimitType.CSRF_TOKEN_ISSUANCE,
                subject,
                60,
                Duration.ofMinutes(1)
        )).thenReturn(new RateLimitDecision(true, 0));
        when(repository.consume(
                PublicAuthRateLimitType.EMAIL_AVAILABILITY,
                subject,
                20,
                Duration.ofMinutes(1)
        )).thenReturn(new RateLimitDecision(true, 0));
        when(repository.consume(
                PublicAuthRateLimitType.REGISTRATION,
                subject,
                5,
                Duration.ofHours(1)
        )).thenReturn(new RateLimitDecision(false, 120));

        assertThat(rateLimiter.consume(
                PublicAuthRateLimitType.CSRF_TOKEN_ISSUANCE,
                "203.0.113.10"
        ).allowed()).isTrue();
        assertThat(rateLimiter.consume(
                PublicAuthRateLimitType.EMAIL_AVAILABILITY,
                "203.0.113.10"
        ).allowed()).isTrue();
        assertThat(rateLimiter.consume(
                PublicAuthRateLimitType.REGISTRATION,
                "203.0.113.10"
        ).retryAfterSeconds()).isEqualTo(120);

        verify(subjectHasher, Mockito.times(3)).hash("ip:203.0.113.10");
    }
}
