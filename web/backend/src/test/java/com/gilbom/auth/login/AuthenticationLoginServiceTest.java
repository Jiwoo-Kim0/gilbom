package com.gilbom.auth.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.gilbom.auth.AuthenticationDefenseService;
import com.gilbom.auth.account.AccountAuthenticationState;
import com.gilbom.auth.account.AccountStatus;
import com.gilbom.auth.audit.AuthenticationAuditService;
import com.gilbom.auth.login.LoginAccountRepository.LoginAccount;
import com.gilbom.auth.mfa.AuthenticationResponse;
import com.gilbom.auth.mfa.MfaAuthenticationCompletionService;
import com.gilbom.auth.model.AuthenticationEvent;
import com.gilbom.auth.model.AuthenticationState;
import com.gilbom.auth.password.PasswordHashingService;
import com.gilbom.auth.ratelimit.AuthenticationRateLimitExceededException;
import com.gilbom.auth.ratelimit.RateLimitDecision;
import com.gilbom.auth.session.AuthenticationSessionService.ChallengeSession;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.common.web.RequestIdFilter;

@ExtendWith(OutputCaptureExtension.class)
class AuthenticationLoginServiceTest {

    private static final UUID USER_ID =
            UUID.fromString("22000000-0000-4000-8000-000000000001");
    private static final UUID REQUEST_ID =
            UUID.fromString("22000000-0000-4000-8000-000000000002");
    private static final Instant NOW =
            Instant.parse("2026-07-27T09:00:00Z");
    private static final String EMAIL = "user@example.com";
    private static final String PASSWORD = "exact password";
    private static final String HASH = "{argon2}$argon2id$encoded";

    private final LoginAccountRepository accountRepository =
            Mockito.mock(LoginAccountRepository.class);
    private final PasswordHashingService passwordHashingService =
            Mockito.mock(PasswordHashingService.class);
    private final AuthenticationDefenseService defenseService =
            Mockito.mock(AuthenticationDefenseService.class);
    private final MfaAuthenticationCompletionService completionService =
            Mockito.mock(MfaAuthenticationCompletionService.class);
    private final AuthenticationAuditService auditService =
            Mockito.mock(AuthenticationAuditService.class);
    private final AuthenticationLoginService service =
            new AuthenticationLoginService(
                    accountRepository,
                    passwordHashingService,
                    defenseService,
                    completionService,
                    auditService,
                    Clock.fixed(NOW, ZoneOffset.UTC)
            );
    private final MockHttpServletRequest servletRequest = request();
    private final MockHttpServletResponse servletResponse =
            new MockHttpServletResponse();

    @Test
    void passwordSuccessReturnsChangeRequiredWithoutAuthenticating() {
        LoginAccount account = activeAccount(true, false, null);
        allowAndMatch(account);
        ChallengeSession challenge = new ChallengeSession(
                AuthenticationState.PASSWORD_CHANGE_REQUIRED,
                NOW.plusSeconds(300)
        );
        when(defenseService.completePasswordVerification(
                Mockito.eq(EMAIL),
                Mockito.eq(USER_ID),
                Mockito.eq(
                        AuthenticationEvent
                                .PASSWORD_VERIFIED_CHANGE_REQUIRED
                ),
                Mockito.eq(servletRequest),
                Mockito.any()
        )).thenReturn(challenge);

        LoginChallengeResponse response =
                (LoginChallengeResponse) service.login(
                        new LoginRequest(EMAIL, PASSWORD),
                        servletRequest,
                        servletResponse
                );

        assertThat(response.loginState())
                .isEqualTo(AuthenticationState.PASSWORD_CHANGE_REQUIRED);
        assertThat(response.challengeExpiresAt())
                .isEqualTo(NOW.plusSeconds(300));
        verify(defenseService).completePasswordVerification(
                Mockito.eq(EMAIL),
                Mockito.eq(USER_ID),
                Mockito.eq(
                        AuthenticationEvent
                                .PASSWORD_VERIFIED_CHANGE_REQUIRED
                ),
                Mockito.eq(servletRequest),
                Mockito.any()
        );
    }

    @Test
    void passwordSuccessSelectsMfaEnrollmentWhenNoActiveFactorExists() {
        LoginAccount account = activeAccount(false, false, null);
        allowAndMatch(account);
        when(defenseService.completePasswordVerification(
                Mockito.eq(EMAIL),
                Mockito.eq(USER_ID),
                Mockito.eq(
                        AuthenticationEvent
                                .PASSWORD_VERIFIED_MFA_ENROLLMENT_REQUIRED
                ),
                Mockito.eq(servletRequest),
                Mockito.any()
        )).thenReturn(new ChallengeSession(
                AuthenticationState.MFA_ENROLLMENT_REQUIRED,
                NOW.plusSeconds(300)
        ));

        LoginChallengeResponse response =
                (LoginChallengeResponse) service.login(
                        new LoginRequest(EMAIL, PASSWORD),
                        servletRequest,
                        servletResponse
                );

        assertThat(response.loginState())
                .isEqualTo(AuthenticationState.MFA_ENROLLMENT_REQUIRED);
    }

    @Test
    void passwordSuccessSelectsMfaVerificationForAnEnrolledAccount() {
        LoginAccount account = activeAccount(false, true, null);
        allowAndMatch(account);
        when(defenseService.completePasswordVerification(
                Mockito.eq(EMAIL),
                Mockito.eq(USER_ID),
                Mockito.eq(
                        AuthenticationEvent
                                .PASSWORD_VERIFIED_MFA_REQUIRED
                ),
                Mockito.eq(servletRequest),
                Mockito.any()
        )).thenReturn(new ChallengeSession(
                AuthenticationState.MFA_REQUIRED,
                NOW.plusSeconds(300)
        ));

        LoginChallengeResponse response =
                (LoginChallengeResponse) service.login(
                        new LoginRequest(EMAIL, PASSWORD),
                        servletRequest,
                        servletResponse
                );

        assertThat(response.loginState())
                .isEqualTo(AuthenticationState.MFA_REQUIRED);
    }

    @Test
    void passwordSuccessCompletesTheExplicitMfaExemptAccount() {
        LoginAccount account = activeAccount(false, false, false, null);
        allowAndMatch(account);
        when(defenseService.completePasswordVerification(
                Mockito.eq(EMAIL),
                Mockito.eq(USER_ID),
                Mockito.eq(
                        AuthenticationEvent.PASSWORD_VERIFIED_MFA_REQUIRED
                ),
                Mockito.eq(servletRequest),
                Mockito.any()
        )).thenReturn(new ChallengeSession(
                AuthenticationState.MFA_REQUIRED,
                NOW.plusSeconds(300)
        ));
        AuthenticationResponse completed = new AuthenticationResponse(
                null,
                "replacement-csrf",
                NOW.plusSeconds(3600),
                false
        );
        when(completionService.completeMfaBypass(
                Mockito.eq(servletRequest),
                Mockito.eq(servletResponse),
                Mockito.eq(USER_ID),
                Mockito.any()
        )).thenReturn(completed);

        LoginResponse response = service.login(
                new LoginRequest(EMAIL, PASSWORD),
                servletRequest,
                servletResponse
        );

        assertThat(response).isSameAs(completed);
        verify(completionService).completeMfaBypass(
                Mockito.eq(servletRequest),
                Mockito.eq(servletResponse),
                Mockito.eq(USER_ID),
                Mockito.any()
        );
    }

    @Test
    void nonexistentAccountUsesDummyHashAndReturnsTheSame401() {
        when(accountRepository.findByNormalizedEmail(EMAIL))
                .thenReturn(Optional.empty());
        when(defenseService.inspectPasswordAttempt(EMAIL))
                .thenReturn(new RateLimitDecision(true, 0));
        when(passwordHashingService.verifyAgainstNullableHash(PASSWORD, null))
                .thenReturn(false);

        assertThatThrownBy(() -> service.login(
                new LoginRequest(EMAIL, PASSWORD),
                servletRequest,
                servletResponse
        )).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.errorCode())
                    .isEqualTo(ErrorCode.AUTH_UNAUTHENTICATED);
            assertThat(exception.getMessage())
                    .isEqualTo("인증 정보가 올바르지 않습니다.");
        });

        verify(defenseService).recordPasswordFailure(
                Mockito.eq(EMAIL),
                Mockito.isNull(),
                Mockito.any()
        );
        verify(defenseService, never()).completePasswordVerification(
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()
        );
    }

    @Test
    void aLockedAccountStillPerformsArgon2VerificationThenUsesTheSame401() {
        LoginAccount account = activeAccount(
                false,
                true,
                NOW.plusSeconds(60)
        );
        allowAndMatch(account);

        assertThatThrownBy(() -> service.login(
                new LoginRequest(EMAIL, PASSWORD),
                servletRequest,
                servletResponse
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.errorCode())
                        .isEqualTo(ErrorCode.AUTH_UNAUTHENTICATED));

        verify(passwordHashingService)
                .verifyAgainstNullableHash(PASSWORD, HASH);
        verify(defenseService).recordPasswordFailure(
                Mockito.eq(EMAIL),
                Mockito.eq(USER_ID),
                Mockito.any()
        );
        verify(defenseService, never()).completePasswordVerification(
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()
        );
    }

    @Test
    void unsupportedStoredHashIsLoggedWithoutSecretsAndReturnsTheSame401(
            CapturedOutput output
    ) {
        String malformedHash = "{bcrypt}$must-not-be-logged";
        String secretPassword = "secret password";
        LoginAccount account = new LoginAccount(
                USER_ID,
                malformedHash,
                new AccountAuthenticationState(
                        AccountStatus.ACTIVE,
                        0,
                        null,
                        null
                ),
                false,
                true,
                true
        );
        when(accountRepository.findByNormalizedEmail(EMAIL))
                .thenReturn(Optional.of(account));
        when(defenseService.inspectPasswordAttempt(EMAIL))
                .thenReturn(new RateLimitDecision(true, 0));
        when(passwordHashingService.verifyAgainstNullableHash(
                secretPassword,
                malformedHash
        )).thenThrow(new IllegalArgumentException("bad encoded password"));

        assertThatThrownBy(() -> service.login(
                new LoginRequest(EMAIL, secretPassword),
                servletRequest,
                servletResponse
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.errorCode())
                        .isEqualTo(ErrorCode.AUTH_UNAUTHENTICATED));

        assertThat(output).contains(
                "category=PASSWORD_HASH_CONFIGURATION"
        );
        assertThat(output).doesNotContain(
                malformedHash,
                secretPassword,
                EMAIL,
                "bad encoded password"
        );
    }

    @Test
    void blockedAccountFailureBucketStopsBeforeArgon2AndReturnsRetryAfter() {
        LoginAccount account = activeAccount(false, true, null);
        when(accountRepository.findByNormalizedEmail(EMAIL))
                .thenReturn(Optional.of(account));
        when(defenseService.inspectPasswordAttempt(EMAIL))
                .thenReturn(new RateLimitDecision(false, 47));

        assertThatThrownBy(() -> service.login(
                new LoginRequest(EMAIL, PASSWORD),
                servletRequest,
                servletResponse
        )).isInstanceOfSatisfying(
                AuthenticationRateLimitExceededException.class,
                exception -> assertThat(exception.retryAfterSeconds())
                        .isEqualTo(47)
        );

        verify(passwordHashingService, never())
                .verifyAgainstNullableHash(Mockito.any(), Mockito.any());
        verify(auditService).recordRateLimitedBestEffort(
                Mockito.any(),
                Mockito.eq(USER_ID)
        );
    }

    private void allowAndMatch(LoginAccount account) {
        when(accountRepository.findByNormalizedEmail(EMAIL))
                .thenReturn(Optional.of(account));
        when(defenseService.inspectPasswordAttempt(EMAIL))
                .thenReturn(new RateLimitDecision(true, 0));
        when(passwordHashingService.verifyAgainstNullableHash(PASSWORD, HASH))
                .thenReturn(true);
    }

    private static LoginAccount activeAccount(
            boolean passwordChangeRequired,
            boolean mfaEnrolled,
            Instant lockedUntil
    ) {
        return activeAccount(
                passwordChangeRequired,
                true,
                mfaEnrolled,
                lockedUntil
        );
    }

    private static LoginAccount activeAccount(
            boolean passwordChangeRequired,
            boolean mfaRequired,
            boolean mfaEnrolled,
            Instant lockedUntil
    ) {
        int failures = lockedUntil == null ? 0 : 5;
        Instant lastFailure =
                lockedUntil == null ? null : lockedUntil.minusSeconds(900);
        return new LoginAccount(
                USER_ID,
                HASH,
                new AccountAuthenticationState(
                        AccountStatus.ACTIVE,
                        failures,
                        lastFailure,
                        lockedUntil
                ),
                passwordChangeRequired,
                mfaRequired,
                mfaEnrolled
        );
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.22");
        request.setAttribute(
                RequestIdFilter.ATTRIBUTE_NAME,
                REQUEST_ID.toString()
        );
        return request;
    }
}
