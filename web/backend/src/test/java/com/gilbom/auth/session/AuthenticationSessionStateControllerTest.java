package com.gilbom.auth.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.CacheControl;

import com.gilbom.auth.model.AuthenticationState;

import jakarta.servlet.http.HttpServletRequest;

class AuthenticationSessionStateControllerTest {

    private static final UUID USER_ID =
            UUID.fromString("18500000-0000-4000-8000-000000000001");
    private static final Instant EXPIRES_AT =
            Instant.parse("2026-07-27T09:05:00Z");

    private final AuthenticationSessionService sessionService =
            mock(AuthenticationSessionService.class);
    private final AuthenticationSessionStateController controller =
            new AuthenticationSessionStateController(sessionService);
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    @Test
    void exposesOnlyTheCookieBoundPreAuthenticationState() {
        when(sessionService.findCurrentState(request))
                .thenReturn(Optional.of(new SessionSecurityState(
                        USER_ID,
                        AuthenticationState.MFA_ENROLLMENT_REQUIRED,
                        EXPIRES_AT,
                        EXPIRES_AT.minusSeconds(300),
                        EXPIRES_AT,
                        0
                )));

        var response = controller.sessionState(request);

        assertThat(response.getHeaders().getCacheControl())
                .isEqualTo(CacheControl.noStore().getHeaderValue());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().authenticationState())
                .isEqualTo(AuthenticationState.MFA_ENROLLMENT_REQUIRED);
        assertThat(response.getBody().challengeExpiresAt())
                .isEqualTo(EXPIRES_AT);
    }

    @Test
    void doesNotExposeAccountDataForAnAnonymousSession() {
        when(sessionService.findCurrentState(request))
                .thenReturn(Optional.empty());

        var response = controller.sessionState(request);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().authenticationState())
                .isEqualTo(AuthenticationState.ANONYMOUS);
        assertThat(response.getBody().challengeExpiresAt()).isNull();
    }
}
