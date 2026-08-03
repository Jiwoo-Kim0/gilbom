package com.gilbom.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.security.core.Authentication;

import com.gilbom.auth.account.AccountLoginProtectionRepository;
import com.gilbom.auth.account.AccountLoginProtectionRepository.AccountFailureState;
import com.gilbom.auth.audit.AuthenticationAuditService;
import com.gilbom.auth.audit.AuthenticationRequestAuditContext;
import com.gilbom.auth.model.AuthenticationState;
import com.gilbom.auth.model.AuthenticationEvent;
import com.gilbom.auth.ratelimit.AuthenticationRateLimitProperties;
import com.gilbom.auth.ratelimit.AuthenticationRateLimiter;
import com.gilbom.auth.ratelimit.PersistentRateLimitRepository.FailureRateLimitDecision;
import com.gilbom.auth.ratelimit.RateLimitDecision;
import com.gilbom.auth.session.AuthenticationSessionService;
import com.gilbom.auth.session.AuthenticationSessionService.AuthenticatedSession;
import com.gilbom.auth.session.AuthenticationSessionService.ChallengeFailure;
import com.gilbom.auth.session.AuthenticationSessionService.ChallengeSession;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

class AuthenticationDefenseServiceTest {

    private static final UUID REQUEST_ID =
            UUID.fromString("21000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID =
            UUID.fromString("21000000-0000-4000-8000-000000000002");
    private static final Instant NOW =
            Instant.parse("2026-07-27T08:00:00Z");
    private static final AuthenticationRequestAuditContext CONTEXT =
            new AuthenticationRequestAuditContext(
                    REQUEST_ID,
                    "203.0.113.14",
                    "test-agent"
            );

    private final AuthenticationRateLimitProperties properties =
            new AuthenticationRateLimitProperties();
    private final AuthenticationRateLimiter rateLimiter =
            Mockito.mock(AuthenticationRateLimiter.class);
    private final AccountLoginProtectionRepository accountRepository =
            Mockito.mock(AccountLoginProtectionRepository.class);
    private final AuthenticationAuditService auditService =
            Mockito.mock(AuthenticationAuditService.class);
    private final AuthenticationSessionService sessionService =
            Mockito.mock(AuthenticationSessionService.class);
    private final AuthenticationDefenseService service =
            new AuthenticationDefenseService(
                    properties,
                    rateLimiter,
                    accountRepository,
                    auditService,
                    sessionService
            );

    @Test
    void fifthKnownPasswordFailureLocksAndAuditsAtomically() {
        when(rateLimiter.recordPasswordFailure("User@Example.COM"))
                .thenReturn(new FailureRateLimitDecision(
                        5,
                        NOW.plusSeconds(900),
                        900
                ));
        when(accountRepository.recordPasswordFailure(
                USER_ID,
                5,
                java.time.Duration.ofMinutes(15),
                java.time.Duration.ofMinutes(15)
        )).thenReturn(java.util.Optional.of(new AccountFailureState(
                5,
                NOW,
                NOW.plusSeconds(900)
        )));

        AuthenticationDefenseService.PasswordFailureOutcome result =
                service.recordPasswordFailure(
                        "User@Example.COM",
                        USER_ID,
                        CONTEXT
                );

        assertThat(result.failureCount()).isEqualTo(5);
        assertThat(result.retryAfterSeconds()).isEqualTo(900);
        assertThat(result.accountTemporarilyLocked()).isTrue();
        verify(auditService).recordLoginFailedRequired(CONTEXT, USER_ID);
        verify(auditService).recordAccountLockedRequired(CONTEXT, USER_ID);
    }

    @Test
    void unknownAccountStillConsumesTheSameHmacFailureBucket() {
        when(rateLimiter.recordPasswordFailure("unknown@example.com"))
                .thenReturn(new FailureRateLimitDecision(1, null, 0));

        service.recordPasswordFailure(
                "unknown@example.com",
                null,
                CONTEXT
        );

        verify(accountRepository, never()).recordPasswordFailure(
                Mockito.any(),
                Mockito.anyInt(),
                Mockito.any(),
                Mockito.any()
        );
        verify(auditService).recordLoginFailedRequired(CONTEXT, null);
    }

    @Test
    void blockedAccountBucketReturnsTheRemainingLockTime() {
        when(rateLimiter.inspectPasswordFailures("user@example.com"))
                .thenReturn(new RateLimitDecision(false, 300));

        RateLimitDecision decision =
                service.inspectPasswordAttempt("user@example.com");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(300);
    }

    @Test
    void passwordVerificationAuditsAndCreatesOnlyAPreAuthChallenge() {
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        ChallengeSession expected = new ChallengeSession(
                AuthenticationState.MFA_REQUIRED,
                NOW.plusSeconds(300)
        );
        when(accountRepository.resetPasswordFailures(USER_ID)).thenReturn(1);
        when(sessionService.beginChallenge(
                request,
                USER_ID,
                AuthenticationEvent.PASSWORD_VERIFIED_MFA_REQUIRED
        )).thenReturn(expected);

        ChallengeSession result = service.completePasswordVerification(
                "user@example.com",
                USER_ID,
                AuthenticationEvent.PASSWORD_VERIFIED_MFA_REQUIRED,
                request,
                CONTEXT
        );

        assertThat(result).isSameAs(expected);
        InOrder order = inOrder(
                rateLimiter,
                accountRepository,
                auditService,
                sessionService
        );
        order.verify(rateLimiter)
                .resetPasswordFailures("user@example.com");
        order.verify(accountRepository).resetPasswordFailures(USER_ID);
        order.verify(auditService)
                .recordPasswordVerifiedRequired(CONTEXT, USER_ID);
        order.verify(sessionService).beginChallenge(
                request,
                USER_ID,
                AuthenticationEvent.PASSWORD_VERIFIED_MFA_REQUIRED
        );
    }

    @Test
    void mfaFailureAuditsThenConsumesChallengeAndAccountLimit() {
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        when(sessionService.recordChallengeFailure(request, USER_ID))
                .thenReturn(new ChallengeFailure(5, 0, true));
        when(rateLimiter.recordMfaFailure(USER_ID))
                .thenReturn(new FailureRateLimitDecision(
                        5,
                        NOW.plusSeconds(900),
                        900
                ));

        AuthenticationDefenseService.MfaFailureOutcome result =
                service.recordMfaFailure(request, USER_ID, CONTEXT);

        assertThat(result.challengeInvalidated()).isTrue();
        assertThat(result.retryAfterSeconds()).isEqualTo(900);
        InOrder order = inOrder(auditService, sessionService, rateLimiter);
        order.verify(auditService)
                .recordMfaChallengeFailedRequired(CONTEXT, USER_ID);
        order.verify(sessionService)
                .recordChallengeFailure(request, USER_ID);
        order.verify(rateLimiter).recordMfaFailure(USER_ID);
    }

    @Test
    void requiredSuccessAuditPrecedesMfaSessionPromotion() {
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
        Authentication authentication = Mockito.mock(Authentication.class);
        AuthenticatedSession expected = new AuthenticatedSession(
                AuthenticationState.AUTHENTICATED,
                NOW.plusSeconds(8 * 60 * 60),
                "csrf"
        );
        when(accountRepository.recordCompletedLogin(USER_ID)).thenReturn(1);
        when(sessionService.completeMfa(
                request,
                response,
                USER_ID,
                authentication
        )).thenReturn(expected);

        AuthenticatedSession result = service.completeMfaAndRecordLogin(
                request,
                response,
                USER_ID,
                authentication,
                CONTEXT
        );

        assertThat(result).isSameAs(expected);
        InOrder order = inOrder(
                accountRepository,
                rateLimiter,
                auditService,
                sessionService
        );
        order.verify(accountRepository).recordCompletedLogin(USER_ID);
        order.verify(rateLimiter).resetMfaFailures(USER_ID);
        order.verify(auditService)
                .recordLoginSucceededRequired(CONTEXT, USER_ID);
        order.verify(sessionService).completeMfa(
                request,
                response,
                USER_ID,
                authentication
        );
    }

    @Test
    void requiredSuccessAuditPrecedesMfaBypassSessionPromotion() {
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
        Authentication authentication = Mockito.mock(Authentication.class);
        AuthenticatedSession expected = new AuthenticatedSession(
                AuthenticationState.AUTHENTICATED,
                NOW.plusSeconds(8 * 60 * 60),
                "csrf"
        );
        when(accountRepository.recordCompletedLogin(USER_ID)).thenReturn(1);
        when(sessionService.completeMfaBypass(
                request,
                response,
                USER_ID,
                authentication
        )).thenReturn(expected);

        AuthenticatedSession result =
                service.completeMfaBypassAndRecordLogin(
                        request,
                        response,
                        USER_ID,
                        authentication,
                        CONTEXT
                );

        assertThat(result).isSameAs(expected);
        InOrder order = inOrder(
                accountRepository,
                rateLimiter,
                auditService,
                sessionService
        );
        order.verify(accountRepository).recordCompletedLogin(USER_ID);
        order.verify(rateLimiter).resetMfaFailures(USER_ID);
        order.verify(auditService)
                .recordLoginSucceededRequired(CONTEXT, USER_ID);
        order.verify(sessionService).completeMfaBypass(
                request,
                response,
                USER_ID,
                authentication
        );
    }

    @Test
    void failedRequiredSuccessAuditPreventsSessionPromotion() {
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
        Authentication authentication = Mockito.mock(Authentication.class);
        when(accountRepository.recordCompletedLogin(USER_ID)).thenReturn(1);
        Mockito.doThrow(new IllegalStateException("audit unavailable"))
                .when(auditService)
                .recordLoginSucceededRequired(CONTEXT, USER_ID);

        assertThatThrownBy(() -> service.completeMfaAndRecordLogin(
                request,
                response,
                USER_ID,
                authentication,
                CONTEXT
        )).isInstanceOf(IllegalStateException.class);

        verify(sessionService, never()).completeMfa(
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()
        );
    }

    @Test
    void logoutRevokesBeforeBestEffortAudit() {
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);

        service.logout(request, USER_ID, CONTEXT);

        InOrder order = inOrder(sessionService, auditService);
        order.verify(sessionService).revokeCurrentServerSession(request);
        order.verify(sessionService).expireCurrentClientSession(request);
        order.verify(auditService).recordLogoutBestEffort(CONTEXT, USER_ID);
    }
}
