package com.gilbom.auth.mfa;

import java.time.Instant;

public record TotpEnrollmentResponse(
        String otpauthUri,
        String manualEntryKey,
        Instant enrollmentExpiresAt
) {
}
