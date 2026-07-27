package com.gilbom.auth.mfa;

import java.time.Instant;
import java.util.List;

public record MfaEnrollmentConfirmationResponse(
        AuthenticatedUserResponse user,
        String csrfToken,
        Instant sessionExpiresAt,
        boolean recoveryCodeUsed,
        List<String> recoveryCodes
) {
}
