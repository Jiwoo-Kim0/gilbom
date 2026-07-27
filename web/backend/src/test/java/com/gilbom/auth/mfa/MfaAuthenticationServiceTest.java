package com.gilbom.auth.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.gilbom.auth.AuthenticationDefenseService;
import com.gilbom.auth.AuthenticationDefenseService.MfaFailureOutcome;
import com.gilbom.auth.audit.AuthenticationAuditService;
import com.gilbom.auth.mfa.MfaFactorRepository.EnrollmentAccount;
import com.gilbom.auth.mfa.MfaFactorRepository.MfaFactor;
import com.gilbom.auth.mfa.MfaFactorRepository.RecoveryCodeRecord;
import com.gilbom.auth.mfa.TotpService.GeneratedTotpSecret;
import com.gilbom.auth.mfa.crypto.EncryptedTotpSecret;
import com.gilbom.auth.mfa.crypto.TotpSecretEncryptionService;
import com.gilbom.auth.model.AuthenticationState;
import com.gilbom.auth.ratelimit.AuthenticationRateLimitExceededException;
import com.gilbom.auth.ratelimit.RateLimitDecision;
import com.gilbom.auth.session.AuthenticationSessionService;
import com.gilbom.auth.session.SessionSecurityState;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.common.web.RequestIdFilter;

class MfaAuthenticationServiceTest {

    private static final UUID USER_ID =
            UUID.fromString("24000000-0000-4000-8000-000000000001");
    private static final UUID FACTOR_ID =
            UUID.fromString("24000000-0000-4000-8000-000000000002");
    private static final Instant NOW =
            Instant.parse("2026-07-27T12:30:00Z");
    private static final byte[] CIPHERTEXT = new byte[36];
    private static final byte[] NONCE = new byte[12];

    private final MfaFactorRepository factorRepository =
            Mockito.mock(MfaFactorRepository.class);
    private final AuthenticationSessionService sessionService =
            Mockito.mock(AuthenticationSessionService.class);
    private final TotpService totpService =
            Mockito.mock(TotpService.class);
    private final TotpSecretEncryptionService encryptionService =
            Mockito.mock(TotpSecretEncryptionService.class);
    private final RecoveryCodeService recoveryCodeService =
            Mockito.mock(RecoveryCodeService.class);
    private final AuthenticationDefenseService defenseService =
            Mockito.mock(AuthenticationDefenseService.class);
    private final AuthenticationAuditService auditService =
            Mockito.mock(AuthenticationAuditService.class);
    private final MfaEnrollmentCommitService enrollmentCommitService =
            Mockito.mock(MfaEnrollmentCommitService.class);
    private final MfaAuthenticationCompletionService completionService =
            Mockito.mock(MfaAuthenticationCompletionService.class);
    private final MfaAuthenticationService service =
            new MfaAuthenticationService(
                    factorRepository,
                    sessionService,
                    totpService,
                    encryptionService,
                    recoveryCodeService,
                    defenseService,
                    auditService,
                    enrollmentCommitService,
                    completionService,
                    Clock.fixed(NOW, ZoneOffset.UTC)
            );
    private final MockHttpServletRequest request = request();
    private final MockHttpServletResponse response =
            new MockHttpServletResponse();

    @Test
    void enrollmentReturnsQrDataButPersistsOnlyTheEncryptedSecret() {
        byte[] rawSecret = new byte[20];
        when(sessionService.findCurrentState(request))
                .thenReturn(Optional.of(challenge(
                        AuthenticationState.MFA_ENROLLMENT_REQUIRED
                )));
        when(factorRepository.findEligibleAccount(USER_ID))
                .thenReturn(Optional.of(new EnrollmentAccount(
                        USER_ID,
                        "operator@example.com"
                )));
        when(factorRepository.hasActiveTotpFactor(USER_ID))
                .thenReturn(false);
        when(totpService.generateSecret()).thenReturn(
                new GeneratedTotpSecret(
                        rawSecret,
                        "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"
                )
        );
        EncryptedTotpSecret encrypted =
                new EncryptedTotpSecret(CIPHERTEXT, NONCE, 1);
        when(encryptionService.encrypt(
                Mockito.eq(USER_ID),
                Mockito.any(UUID.class),
                Mockito.same(rawSecret)
        )).thenReturn(encrypted);
        when(totpService.buildOtpAuthUri(
                "operator@example.com",
                "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"
        )).thenReturn("otpauth://totp/GILBOM:operator");

        TotpEnrollmentResponse result = service.enroll(request);

        assertThat(result.otpauthUri())
                .isEqualTo("otpauth://totp/GILBOM:operator");
        assertThat(result.manualEntryKey())
                .isEqualTo("JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP");
        assertThat(result.enrollmentExpiresAt())
                .isEqualTo(NOW.plusSeconds(300));
        assertThat(rawSecret).containsOnly((byte) 0);
        verify(enrollmentCommitService).commit(
                Mockito.eq(request),
                Mockito.eq(USER_ID),
                Mockito.any(UUID.class),
                Mockito.same(encrypted),
                Mockito.any()
        );
    }

    @Test
    void invalidTotpConsumesOneChallengeAttemptAndUsesTheGeneric401() {
        byte[] decrypted = new byte[20];
        MfaFactor factor = activeFactor();
        when(sessionService.findCurrentState(request))
                .thenReturn(Optional.of(challenge(
                        AuthenticationState.MFA_REQUIRED
                )));
        when(defenseService.inspectMfaAttempt(USER_ID))
                .thenReturn(new RateLimitDecision(true, 0));
        when(factorRepository.findCurrentTotpFactor(
                USER_ID,
                MfaFactorStatus.ACTIVE
        )).thenReturn(Optional.of(factor));
        when(encryptionService.decrypt(
                USER_ID,
                FACTOR_ID,
                factor.encryptedSecret()
        )).thenReturn(decrypted);
        when(totpService.verify(
                Mockito.same(decrypted),
                Mockito.eq("123456"),
                Mockito.eq(NOW),
                Mockito.isNull()
        )).thenReturn(OptionalLong.empty());
        when(defenseService.recordMfaFailure(
                Mockito.eq(request),
                Mockito.eq(USER_ID),
                Mockito.any()
        )).thenReturn(new MfaFailureOutcome(1, 4, false, 0));

        assertThatThrownBy(() -> service.verifyTotp(
                new TotpCodeRequest("123456"),
                request,
                response
        )).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.errorCode())
                    .isEqualTo(ErrorCode.AUTH_UNAUTHENTICATED);
            assertThat(exception.getMessage())
                    .isEqualTo("인증 정보가 올바르지 않습니다.");
        });

        assertThat(decrypted).containsOnly((byte) 0);
        verify(completionService, never()).completeTotp(
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.anyLong(),
                Mockito.any()
        );
    }

    @Test
    void fifthInvalidMfaAttemptInvalidatesChallengeAndReturns429() {
        when(sessionService.findCurrentState(request))
                .thenReturn(Optional.of(challenge(
                        AuthenticationState.MFA_REQUIRED
                )));
        when(defenseService.inspectMfaAttempt(USER_ID))
                .thenReturn(new RateLimitDecision(true, 0));
        when(factorRepository.findCurrentTotpFactor(
                USER_ID,
                MfaFactorStatus.ACTIVE
        )).thenReturn(Optional.empty());
        when(defenseService.recordMfaFailure(
                Mockito.eq(request),
                Mockito.eq(USER_ID),
                Mockito.any()
        )).thenReturn(new MfaFailureOutcome(5, 0, true, 900));

        assertThatThrownBy(() -> service.verifyTotp(
                new TotpCodeRequest("123456"),
                request,
                response
        )).isInstanceOfSatisfying(
                AuthenticationRateLimitExceededException.class,
                exception -> assertThat(exception.retryAfterSeconds())
                        .isEqualTo(900)
        );
    }

    @Test
    void anAlreadyUsedRecoveryCodeStillPerformsHashVerification() {
        MfaFactor factor = activeFactor();
        RecoveryCodeRecord usedCode = new RecoveryCodeRecord(
                UUID.fromString(
                        "24000000-0000-4000-8000-000000000003"
                ),
                FACTOR_ID,
                1,
                "{argon2}$argon2id$hash",
                NOW.minusSeconds(1),
                null
        );
        String rawCode = "01-AAAA-AAAA-AAAA-AAAA";
        when(sessionService.findCurrentState(request))
                .thenReturn(Optional.of(challenge(
                        AuthenticationState.MFA_REQUIRED
                )));
        when(defenseService.inspectMfaAttempt(USER_ID))
                .thenReturn(new RateLimitDecision(true, 0));
        when(factorRepository.findCurrentTotpFactor(
                USER_ID,
                MfaFactorStatus.ACTIVE
        )).thenReturn(Optional.of(factor));
        when(recoveryCodeService.parsePosition(rawCode))
                .thenReturn(java.util.OptionalInt.of(1));
        when(factorRepository.findRecoveryCode(FACTOR_ID, 1))
                .thenReturn(Optional.of(usedCode));
        when(recoveryCodeService.verify(
                rawCode,
                usedCode.encodedHash()
        )).thenReturn(true);
        when(defenseService.recordMfaFailure(
                Mockito.eq(request),
                Mockito.eq(USER_ID),
                Mockito.any()
        )).thenReturn(new MfaFailureOutcome(1, 4, false, 0));

        assertThatThrownBy(() -> service.verifyRecoveryCode(
                new RecoveryCodeRequest(rawCode),
                request,
                response
        )).isInstanceOf(BusinessException.class);

        verify(recoveryCodeService).verify(
                rawCode,
                usedCode.encodedHash()
        );
        verify(completionService, never()).completeRecovery(
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()
        );
    }

    private static SessionSecurityState challenge(
            AuthenticationState state
    ) {
        return new SessionSecurityState(
                USER_ID,
                state,
                NOW.plusSeconds(300),
                NOW,
                NOW.plusSeconds(300),
                0
        );
    }

    private static MfaFactor activeFactor() {
        return new MfaFactor(
                FACTOR_ID,
                USER_ID,
                MfaFactorStatus.ACTIVE,
                new EncryptedTotpSecret(CIPHERTEXT, NONCE, 1),
                null
        );
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request =
                new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.24");
        request.setAttribute(
                RequestIdFilter.ATTRIBUTE_NAME,
                UUID.fromString(
                        "24000000-0000-4000-8000-000000000004"
                ).toString()
        );
        return request;
    }
}
