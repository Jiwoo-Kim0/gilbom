package com.gilbom.auth.mfa;

import java.time.Instant;

import com.gilbom.auth.login.LoginResponse;

public record AuthenticationResponse(
        AuthenticatedUserResponse user,
        String csrfToken,
        Instant sessionExpiresAt,
        boolean recoveryCodeUsed
) implements LoginResponse {
}
