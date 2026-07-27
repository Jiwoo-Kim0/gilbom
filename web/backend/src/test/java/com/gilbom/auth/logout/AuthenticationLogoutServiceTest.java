package com.gilbom.auth.logout;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;

import com.gilbom.auth.AuthenticationDefenseService;
import com.gilbom.auth.model.AuthenticationState;
import com.gilbom.auth.session.AuthenticationCookieService;
import com.gilbom.auth.session.AuthenticationSessionService;
import com.gilbom.auth.session.SessionSecurityState;
import com.gilbom.common.error.BusinessException;

class AuthenticationLogoutServiceTest {

    private static final UUID USER_ID =
            UUID.fromString("29000000-0000-4000-8000-000000000001");
    private static final Instant NOW =
            Instant.parse("2026-07-27T15:00:00Z");

    private final AuthenticationSessionService sessionService =
            Mockito.mock(AuthenticationSessionService.class);
    private final AuthenticationDefenseService defenseService =
            Mockito.mock(AuthenticationDefenseService.class);
    private final AuthenticationCookieService cookieService =
            Mockito.mock(AuthenticationCookieService.class);
    private final AuthenticationLogoutService service =
            new AuthenticationLogoutService(
                    sessionService,
                    defenseService,
                    cookieService
            );
    private final MockHttpServletRequest request =
            new MockHttpServletRequest();
    private final MockHttpServletResponse response =
            new MockHttpServletResponse();

    @Test
    void serverSessionIsRevokedBeforeTheCookieExpires() {
        Authentication authentication =
                Mockito.mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn(USER_ID.toString());
        when(sessionService.findCurrentState(request))
                .thenReturn(Optional.of(state(
                        AuthenticationState.AUTHENTICATED
                )));

        service.logout(authentication, request, response);

        InOrder order = inOrder(defenseService, cookieService);
        order.verify(defenseService).logout(
                Mockito.eq(request),
                Mockito.eq(USER_ID),
                Mockito.any()
        );
        order.verify(cookieService).expire(response);
    }

    @Test
    void aPreAuthenticationChallengeCanAlsoLogout() {
        when(sessionService.findCurrentState(request))
                .thenReturn(Optional.of(state(
                        AuthenticationState.MFA_REQUIRED
                )));

        service.logout(null, request, response);

        verify(defenseService).logout(
                Mockito.eq(request),
                Mockito.eq(USER_ID),
                Mockito.any()
        );
    }

    @Test
    void principalAndSessionOwnershipMismatchIsRejected() {
        Authentication authentication =
                Mockito.mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn(
                UUID.fromString(
                        "29000000-0000-4000-8000-000000000099"
                ).toString()
        );
        when(sessionService.findCurrentState(request))
                .thenReturn(Optional.of(state(
                        AuthenticationState.AUTHENTICATED
                )));

        assertThatThrownBy(() ->
                service.logout(authentication, request, response))
                .isInstanceOf(BusinessException.class);

        verify(defenseService, never()).logout(
                Mockito.any(),
                Mockito.any(),
                Mockito.any()
        );
    }

    private static SessionSecurityState state(
            AuthenticationState authenticationState
    ) {
        return new SessionSecurityState(
                USER_ID,
                authenticationState,
                NOW.plusSeconds(3600),
                authenticationState.isFullyAuthenticated() ? null : NOW,
                authenticationState.isFullyAuthenticated()
                        ? null
                        : NOW.plusSeconds(300),
                0
        );
    }
}
