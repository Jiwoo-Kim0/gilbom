package com.gilbom.auth.mfa;

import java.time.Instant;

public record AuthenticationResponse(
        AuthenticatedUserResponse user,
        String csrfToken,
        Instant sessionExpiresAt,
        boolean recoveryCodeUsed
) {
}
