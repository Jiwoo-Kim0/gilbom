package com.gilbom.auth.login;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

import com.gilbom.auth.model.AuthenticationState;
import com.gilbom.auth.session.AuthenticationSessionService.ChallengeSession;

public record LoginChallengeResponse(
        AuthenticationState loginState,
        Instant challengeExpiresAt
) {

    private static final Set<AuthenticationState> EXPOSED_STATES = Set.of(
            AuthenticationState.PASSWORD_CHANGE_REQUIRED,
            AuthenticationState.MFA_ENROLLMENT_REQUIRED,
            AuthenticationState.MFA_REQUIRED
    );

    public LoginChallengeResponse {
        Objects.requireNonNull(loginState, "loginState must not be null");
        Objects.requireNonNull(
                challengeExpiresAt,
                "challengeExpiresAt must not be null"
        );
        if (!EXPOSED_STATES.contains(loginState)) {
            throw new IllegalArgumentException(
                    "Only pre-authentication states may be returned"
            );
        }
    }

    public static LoginChallengeResponse from(ChallengeSession challenge) {
        Objects.requireNonNull(challenge, "challenge must not be null");
        return new LoginChallengeResponse(
                challenge.authenticationState(),
                challenge.challengeExpiresAt()
        );
    }
}
