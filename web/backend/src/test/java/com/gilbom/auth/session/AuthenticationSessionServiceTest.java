package com.gilbom.auth.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import com.gilbom.auth.model.AuthenticationEvent;
import com.gilbom.auth.model.AuthenticationState;
import com.gilbom.auth.model.AuthenticationStateMachine;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

class AuthenticationSessionServiceTest {

    private static final UUID USER_ID =
            UUID.fromString("18000000-0000-4000-8000-000000000001");
    private static final Instant NOW =
            Instant.parse("2026-07-27T06:00:00Z");

    private final SessionSecurityStateRepository repository =
            mock(SessionSecurityStateRepository.class);
    private final CsrfTokenRepository csrfTokenRepository =
            mock(CsrfTokenRepository.class);
    private final CsrfTokenRequestHandler csrfTokenRequestHandler =
            mock(CsrfTokenRequestHandler.class);
    private final SecurityContextRepository securityContextRepository =
            mock(SecurityContextRepository.class);
    private final AuthenticationSessionProperties properties =
            new AuthenticationSessionProperties();
    private final AuthenticationSessionService service =
            new AuthenticationSessionService(
                    repository,
                    new AuthenticationStateMachine(),
                    properties,
                    csrfTokenRepository,
                    csrfTokenRequestHandler,
                    securityContextRepository,
                    Clock.fixed(NOW, ZoneOffset.UTC)
            );

    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);
    private final HttpSession session = mock(HttpSession.class);

    @BeforeEach
    void setUp() {
        properties.validate();
        when(request.getSession(false)).thenReturn(session);
        when(session.getId()).thenReturn("old-session-id");
        SecurityContextHolder.clearContext();
    }

    @Test
    void createsAFiveMinutePreAuthenticationChallenge() {
        when(repository.findBySessionId("old-session-id"))
                .thenReturn(Optional.empty());
        when(repository.createChallenge(
                "old-session-id",
                USER_ID,
                AuthenticationState.MFA_REQUIRED,
                NOW,
                NOW.plusSeconds(300)
        )).thenReturn(true);

        AuthenticationSessionService.ChallengeSession result =
                service.beginChallenge(
                        request,
                        USER_ID,
                        AuthenticationEvent.PASSWORD_VERIFIED_MFA_REQUIRED
                );

        assertThat(result.authenticationState())
                .isEqualTo(AuthenticationState.MFA_REQUIRED);
        assertThat(result.challengeExpiresAt())
                .isEqualTo(NOW.plusSeconds(300));
        verify(session).setMaxInactiveInterval(1800);
    }

    @Test
    void mfaSuccessRotatesSessionAndCsrfBeforeSavingAuthentication() {
        SessionSecurityState challenge = new SessionSecurityState(
                USER_ID,
                AuthenticationState.MFA_REQUIRED,
                NOW.plusSeconds(300),
                NOW,
                NOW.plusSeconds(300),
                0
        );
        when(repository.findBySessionId("old-session-id"))
                .thenReturn(Optional.of(challenge));
        when(repository.promoteAuthenticated(
                "old-session-id",
                USER_ID,
                AuthenticationState.MFA_REQUIRED,
                NOW,
                NOW.plusSeconds(8 * 60 * 60),
                1800
        )).thenReturn(true);
        when(request.changeSessionId()).thenReturn("new-session-id");
        DefaultCsrfToken replacement = new DefaultCsrfToken(
                "X-CSRF-TOKEN",
                "_csrf",
                "replacement-csrf"
        );
        when(csrfTokenRepository.generateToken(request))
                .thenReturn(replacement);
        DefaultCsrfToken responseToken = new DefaultCsrfToken(
                "X-CSRF-TOKEN",
                "_csrf",
                "masked-replacement-csrf"
        );
        when(request.getAttribute(CsrfToken.class.getName()))
                .thenReturn(responseToken);
        Authentication authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        USER_ID.toString(),
                        null,
                        List.of()
                );

        AuthenticationSessionService.AuthenticatedSession result =
                service.completeMfa(
                        request,
                        response,
                        USER_ID,
                        authentication
                );

        assertThat(result.authenticationState())
                .isEqualTo(AuthenticationState.AUTHENTICATED);
        assertThat(result.absoluteExpiresAt())
                .isEqualTo(NOW.plusSeconds(8 * 60 * 60));
        assertThat(result.csrfToken()).isEqualTo("masked-replacement-csrf");

        InOrder order = inOrder(
                repository,
                request,
                csrfTokenRepository,
                csrfTokenRequestHandler,
                securityContextRepository
        );
        order.verify(repository).promoteAuthenticated(
                "old-session-id",
                USER_ID,
                AuthenticationState.MFA_REQUIRED,
                NOW,
                NOW.plusSeconds(8 * 60 * 60),
                1800
        );
        order.verify(request).changeSessionId();
        order.verify(securityContextRepository).saveContext(
                any(SecurityContext.class),
                any(HttpServletRequest.class),
                any(HttpServletResponse.class)
        );
        order.verify(csrfTokenRepository).saveToken(null, request, response);
        order.verify(csrfTokenRepository).generateToken(request);
        order.verify(csrfTokenRepository).saveToken(
                replacement,
                request,
                response
        );
        order.verify(csrfTokenRequestHandler).handle(
                any(HttpServletRequest.class),
                any(HttpServletResponse.class),
                any()
        );
    }

    @Test
    void rejectsAnEmailPrincipalInsteadOfTheInternalUserId() {
        when(repository.findBySessionId("old-session-id"))
                .thenReturn(Optional.of(new SessionSecurityState(
                        USER_ID,
                        AuthenticationState.MFA_REQUIRED,
                        NOW.plusSeconds(300),
                        NOW,
                        NOW.plusSeconds(300),
                        0
                )));
        Authentication authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        "user@example.com",
                        null,
                        List.of()
                );

        assertThatThrownBy(() -> service.completeMfa(
                request,
                response,
                USER_ID,
                authentication
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "Authenticated principal must use the internal user ID");
    }

    @Test
    void passwordOrMfaChangeCanInvalidateEveryUserSession() {
        when(repository.findBySessionId("old-session-id"))
                .thenReturn(Optional.of(new SessionSecurityState(
                        USER_ID,
                        AuthenticationState.AUTHENTICATED,
                        NOW.plusSeconds(3600),
                        null,
                        null,
                        0
                )));
        when(repository.deleteAllByUserId(USER_ID)).thenReturn(2);

        int invalidated = service.invalidateAllUserSessions(USER_ID, request);

        assertThat(invalidated).isEqualTo(2);
        verify(session).invalidate();
        verify(repository).deleteAllByUserId(USER_ID);
    }

    @Test
    void aCommittedGlobalRevocationCanExpireOnlyTheCurrentClientSession() {
        service.expireCurrentClientSession(request);

        verify(session).invalidate();
        verify(repository, never()).deleteBySessionId(
                org.mockito.ArgumentMatchers.anyString()
        );
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .isNull();
    }

    @Test
    void logoutCommitsTheServerRowDeletionBeforeInvalidatingHttpSession() {
        service.revokeCurrentServerSession(request);

        verify(repository).deleteBySessionId("old-session-id");
        verify(session, never()).invalidate();
    }

    @Test
    void initialPasswordChangePreservesOnlyItsCurrentChallenge() {
        when(repository.findBySessionId("old-session-id"))
                .thenReturn(Optional.of(new SessionSecurityState(
                        USER_ID,
                        AuthenticationState.PASSWORD_CHANGE_REQUIRED,
                        NOW.plusSeconds(300),
                        NOW,
                        NOW.plusSeconds(300),
                        0
                )));
        when(repository.deleteOtherByUserId(
                "old-session-id",
                USER_ID
        )).thenReturn(2);

        int invalidated =
                service.invalidateOtherUserSessions(USER_ID, request);

        assertThat(invalidated).isEqualTo(2);
        verify(repository).deleteOtherByUserId(
                "old-session-id",
                USER_ID
        );
    }

    @Test
    void fifthChallengeFailureInvalidatesTheSession() {
        SessionSecurityState challenge = new SessionSecurityState(
                USER_ID,
                AuthenticationState.MFA_REQUIRED,
                NOW.plusSeconds(300),
                NOW,
                NOW.plusSeconds(300),
                4
        );
        when(repository.findBySessionId("old-session-id"))
                .thenReturn(Optional.of(challenge));
        when(repository.incrementChallengeFailure(
                "old-session-id",
                USER_ID,
                AuthenticationState.MFA_REQUIRED,
                5,
                NOW
        )).thenReturn(Optional.of(5));

        AuthenticationSessionService.ChallengeFailure result =
                service.recordChallengeFailure(request, USER_ID);

        assertThat(result.failureCount()).isEqualTo(5);
        assertThat(result.remainingAttempts()).isZero();
        assertThat(result.sessionInvalidated()).isTrue();
        verify(repository).deleteBySessionId("old-session-id");
        verify(session).invalidate();
    }
}
