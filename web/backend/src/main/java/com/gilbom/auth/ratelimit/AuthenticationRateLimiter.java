package com.gilbom.auth.ratelimit;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.gilbom.auth.account.EmailAddressPolicy;
import com.gilbom.auth.ratelimit.PersistentRateLimitRepository.FailureRateLimitDecision;
import com.gilbom.auth.ratelimit.RateLimitSubjectHasher.HashedRateLimitSubject;

@Service
public class AuthenticationRateLimiter {

    private final AuthenticationRateLimitProperties properties;
    private final RateLimitSubjectHasher subjectHasher;
    private final PersistentRateLimitRepository repository;

    public AuthenticationRateLimiter(
            AuthenticationRateLimitProperties properties,
            RateLimitSubjectHasher subjectHasher,
            PersistentRateLimitRepository repository
    ) {
        this.properties = properties;
        this.subjectHasher = subjectHasher;
        this.repository = repository;
    }

    public RateLimitDecision consumeIpAuthenticationRequest(
            String clientIpAddress
    ) {
        if (clientIpAddress == null || clientIpAddress.isBlank()) {
            throw new IllegalArgumentException(
                    "Client IP address must not be blank");
        }
        return repository.consume(
                AuthenticationRateLimitType.IP_AUTHENTICATION_REQUEST,
                subjectHasher.hash("ip:" + clientIpAddress),
                properties.getIpMaximumRequests(),
                properties.getIpWindow()
        );
    }

    public RateLimitDecision inspectPasswordFailures(String email) {
        return repository.inspect(
                AuthenticationRateLimitType.ACCOUNT_PASSWORD_FAILURE,
                passwordSubject(email)
        );
    }

    public FailureRateLimitDecision recordPasswordFailure(String email) {
        return repository.recordFailure(
                AuthenticationRateLimitType.ACCOUNT_PASSWORD_FAILURE,
                passwordSubject(email),
                properties.getPasswordMaximumFailures(),
                properties.getPasswordFailureWindow(),
                properties.getTemporaryLockDuration()
        );
    }

    public int resetPasswordFailures(String email) {
        return repository.reset(
                AuthenticationRateLimitType.ACCOUNT_PASSWORD_FAILURE,
                passwordSubject(email)
        );
    }

    public RateLimitDecision inspectMfaFailures(UUID userId) {
        return repository.inspect(
                AuthenticationRateLimitType.ACCOUNT_MFA_FAILURE,
                mfaSubject(userId)
        );
    }

    public FailureRateLimitDecision recordMfaFailure(UUID userId) {
        return repository.recordFailure(
                AuthenticationRateLimitType.ACCOUNT_MFA_FAILURE,
                mfaSubject(userId),
                properties.getMfaMaximumFailures(),
                properties.getMfaFailureWindow(),
                properties.getTemporaryLockDuration()
        );
    }

    public int resetMfaFailures(UUID userId) {
        return repository.reset(
                AuthenticationRateLimitType.ACCOUNT_MFA_FAILURE,
                mfaSubject(userId)
        );
    }

    private HashedRateLimitSubject passwordSubject(String email) {
        String normalizedEmail = EmailAddressPolicy.normalize(email);
        return subjectHasher.hash("account-password:" + normalizedEmail);
    }

    private HashedRateLimitSubject mfaSubject(UUID userId) {
        if (userId == null) {
            throw new IllegalArgumentException("User ID must not be null");
        }
        return subjectHasher.hash("account-mfa:" + userId);
    }
}
