package com.gilbom.auth.session;

import java.time.Instant;
import java.util.UUID;

import com.gilbom.auth.model.AuthenticationState;

public record SessionSecurityState(
        UUID userId,
        AuthenticationState authenticationState,
        Instant absoluteExpiresAt,
        Instant challengeStartedAt,
        Instant challengeExpiresAt,
        int challengeFailureCount
) {

    public boolean isExpiredAt(Instant now) {
        if (absoluteExpiresAt != null && !absoluteExpiresAt.isAfter(now)) {
            return true;
        }
        return challengeExpiresAt != null && !challengeExpiresAt.isAfter(now);
    }

    public boolean belongsTo(UUID expectedUserId) {
        return userId != null && userId.equals(expectedUserId);
    }
}
