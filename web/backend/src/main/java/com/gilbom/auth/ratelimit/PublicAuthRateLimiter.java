package com.gilbom.auth.ratelimit;

import org.springframework.stereotype.Service;

import com.gilbom.auth.ratelimit.RateLimitSubjectHasher.HashedRateLimitSubject;

@Service
public class PublicAuthRateLimiter {

    private final PublicAuthRateLimitProperties properties;
    private final RateLimitSubjectHasher subjectHasher;
    private final PersistentRateLimitRepository repository;

    public PublicAuthRateLimiter(
            PublicAuthRateLimitProperties properties,
            RateLimitSubjectHasher subjectHasher,
            PersistentRateLimitRepository repository
    ) {
        this.properties = properties;
        this.subjectHasher = subjectHasher;
        this.repository = repository;
    }

    public RateLimitDecision consume(
            PublicAuthRateLimitType limitType,
            String clientIpAddress
    ) {
        if (clientIpAddress == null || clientIpAddress.isBlank()) {
            throw new IllegalArgumentException(
                    "Client IP address must not be blank");
        }
        HashedRateLimitSubject subject =
                subjectHasher.hash("ip:" + clientIpAddress);

        return switch (limitType) {
            case CSRF_TOKEN_ISSUANCE -> repository.consume(
                    limitType,
                    subject,
                    properties.getCsrfTokenIssuanceMaxRequests(),
                    properties.getCsrfTokenIssuanceWindow()
            );
            case EMAIL_AVAILABILITY -> repository.consume(
                    limitType,
                    subject,
                    properties.getEmailAvailabilityMaxRequests(),
                    properties.getEmailAvailabilityWindow()
            );
            case REGISTRATION -> repository.consume(
                    limitType,
                    subject,
                    properties.getRegistrationMaxRequests(),
                    properties.getRegistrationWindow()
            );
        };
    }

}
