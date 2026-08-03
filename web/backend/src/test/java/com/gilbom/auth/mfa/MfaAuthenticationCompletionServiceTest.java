package com.gilbom.auth.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.gilbom.auth.AuthenticationDefenseService;
import com.gilbom.auth.audit.AuthenticationAuditService;
import com.gilbom.auth.audit.AuthenticationRequestAuditContext;
import com.gilbom.auth.mfa.AuthenticationUserRepository.AuthenticationUser;
import com.gilbom.auth.mfa.MfaFactorRepository.RecoveryCodeRecord;
import com.gilbom.auth.model.AuthenticationState;
import com.gilbom.auth.session.AuthenticationSessionService;
import com.gilbom.auth.session.AuthenticationSessionService.AuthenticatedSession;
import com.gilbom.auth.session.SessionSecurityState;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

class MfaAuthenticationCompletionServiceTest {

    private static final UUID USER_ID =
            UUID.fromString("23000000-0000-4000-8000-000000000001");
    private static final UUID FACTOR_ID =
            UUID.fromString("23000000-0000-4000-8000-000000000002");
    private static final Instant NOW =
            Instant.parse("2026-07-27T12:00:00Z");

    private final MfaFactorRepository factorRepository =
            Mockito.mock(MfaFactorRepository.class);
    private final AuthenticationUserRepository userRepository =
            Mockito.mock(AuthenticationUserRepository.class);
    private final AuthenticationSessionService sessionService =
            Mockito.mock(AuthenticationSessionService.class);
    private final AuthenticationAuditService auditService =
            Mockito.mock(AuthenticationAuditService.class);
    private final AuthenticationDefenseService defenseService =
            Mockito.mock(AuthenticationDefenseService.class);
    private final MfaAuthenticationCompletionService service =
            new MfaAuthenticationCompletionService(
                    factorRepository,
                    userRepository,
                    sessionService,
                    auditService,
                    defenseService
            );
    private final MockHttpServletRequest request =
            new MockHttpServletRequest();
    private final MockHttpServletResponse response =
            new MockHttpServletResponse();
    private final AuthenticationRequestAuditContext auditContext =
            new AuthenticationRequestAuditContext(
                    UUID.fromString(
                            "23000000-0000-4000-8000-000000000003"
                    ),
                    "203.0.113.23",
                    null
            );

    @Test
    void totpStepIsConsumedBeforeTheSessionBecomesAuthenticated() {
        when(sessionService.findCurrentState(request))
                .thenReturn(Optional.of(challenge(
                        AuthenticationState.MFA_REQUIRED
                )));
        when(factorRepository.advanceActiveTotpTimeStep(
                USER_ID,
                FACTOR_ID,
                99
        )).thenReturn(1);
        when(userRepository.findActiveUser(USER_ID))
                .thenReturn(Optional.of(user()));
        when(defenseService.completeMfaAndRecordLogin(
                Mockito.eq(request),
                Mockito.eq(response),
                Mockito.eq(USER_ID),
                Mockito.any(),
                Mockito.eq(auditContext)
        )).thenReturn(new AuthenticatedSession(
                AuthenticationState.AUTHENTICATED,
                NOW.plusSeconds(8 * 60 * 60),
                "replacement-csrf"
        ));

        AuthenticationResponse result = service.completeTotp(
                request,
                response,
                USER_ID,
                FACTOR_ID,
                99,
                auditContext
        );

        assertThat(result.csrfToken()).isEqualTo("replacement-csrf");
        assertThat(result.recoveryCodeUsed()).isFalse();
        assertThat(result.user().permissions())
                .containsExactly("ROBOT_READ");

        InOrder order = inOrder(factorRepository, defenseService);
        order.verify(factorRepository).advanceActiveTotpTimeStep(
                USER_ID,
                FACTOR_ID,
                99
        );
        order.verify(defenseService).completeMfaAndRecordLogin(
                Mockito.eq(request),
                Mockito.eq(response),
                Mockito.eq(USER_ID),
                Mockito.any(),
                Mockito.eq(auditContext)
        );
    }

    @Test
    void aConcurrentReuseOfTheSameTimeStepCannotAuthenticate() {
        when(sessionService.findCurrentState(request))
                .thenReturn(Optional.of(challenge(
                        AuthenticationState.MFA_REQUIRED
                )));
        when(factorRepository.advanceActiveTotpTimeStep(
                USER_ID,
                FACTOR_ID,
                99
        )).thenReturn(0);

        assertThatThrownBy(() -> service.completeTotp(
                request,
                response,
                USER_ID,
                FACTOR_ID,
                99,
                auditContext
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.errorCode())
                        .isEqualTo(ErrorCode.AUTH_UNAUTHENTICATED)
        );

        verify(defenseService, never()).completeMfaAndRecordLogin(
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()
        );
    }

    @Test
    void recoveryCodeIsConsumedAndAuditedBeforeAuthentication() {
        RecoveryCodeRecord recoveryCode = new RecoveryCodeRecord(
                UUID.fromString(
                        "23000000-0000-4000-8000-000000000004"
                ),
                FACTOR_ID,
                1,
                "{argon2}$argon2id$hash",
                null,
                null
        );
        when(sessionService.findCurrentState(request))
                .thenReturn(Optional.of(challenge(
                        AuthenticationState.MFA_REQUIRED
                )));
        when(factorRepository.consumeRecoveryCode(
                USER_ID,
                FACTOR_ID,
                recoveryCode.recoveryCodeId()
        )).thenReturn(1);
        when(userRepository.findActiveUser(USER_ID))
                .thenReturn(Optional.of(user()));
        when(defenseService.completeMfaAndRecordLogin(
                Mockito.eq(request),
                Mockito.eq(response),
                Mockito.eq(USER_ID),
                Mockito.any(),
                Mockito.eq(auditContext)
        )).thenReturn(new AuthenticatedSession(
                AuthenticationState.AUTHENTICATED,
                NOW.plusSeconds(8 * 60 * 60),
                "replacement-csrf"
        ));

        AuthenticationResponse result = service.completeRecovery(
                request,
                response,
                USER_ID,
                FACTOR_ID,
                recoveryCode,
                auditContext
        );

        assertThat(result.recoveryCodeUsed()).isTrue();
        InOrder order = inOrder(
                factorRepository,
                auditService,
                defenseService
        );
        order.verify(factorRepository).consumeRecoveryCode(
                USER_ID,
                FACTOR_ID,
                recoveryCode.recoveryCodeId()
        );
        order.verify(auditService).recordRecoveryCodeUsedRequired(
                auditContext,
                USER_ID
        );
        order.verify(defenseService).completeMfaAndRecordLogin(
                Mockito.eq(request),
                Mockito.eq(response),
                Mockito.eq(USER_ID),
                Mockito.any(),
                Mockito.eq(auditContext)
        );
    }

    @Test
    void explicitMfaExemptionCompletesWithoutASecondFactor() {
        when(userRepository.isMfaExemptUser(USER_ID)).thenReturn(true);
        when(sessionService.findCurrentState(request))
                .thenReturn(Optional.of(challenge(
                        AuthenticationState.MFA_REQUIRED
                )));
        when(userRepository.findActiveUser(USER_ID))
                .thenReturn(Optional.of(user()));
        when(defenseService.completeMfaBypassAndRecordLogin(
                Mockito.eq(request),
                Mockito.eq(response),
                Mockito.eq(USER_ID),
                Mockito.any(),
                Mockito.eq(auditContext)
        )).thenReturn(new AuthenticatedSession(
                AuthenticationState.AUTHENTICATED,
                NOW.plusSeconds(8 * 60 * 60),
                "replacement-csrf"
        ));

        AuthenticationResponse result = service.completeMfaBypass(
                request,
                response,
                USER_ID,
                auditContext
        );

        assertThat(result.csrfToken()).isEqualTo("replacement-csrf");
        verify(defenseService).completeMfaBypassAndRecordLogin(
                Mockito.eq(request),
                Mockito.eq(response),
                Mockito.eq(USER_ID),
                Mockito.any(),
                Mockito.eq(auditContext)
        );
    }

    @Test
    void nonExemptAccountCannotUseTheMfaBypassCompletion() {
        when(userRepository.isMfaExemptUser(USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.completeMfaBypass(
                request,
                response,
                USER_ID,
                auditContext
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.errorCode())
                        .isEqualTo(ErrorCode.AUTH_UNAUTHENTICATED)
        );

        verify(defenseService, never())
                .completeMfaBypassAndRecordLogin(
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

    private static AuthenticationUser user() {
        return new AuthenticationUser(
                USER_ID,
                "운영자",
                List.of(new RoleSummaryResponse(
                        "OPERATOR",
                        "운영자"
                )),
                List.of("ROBOT_READ")
        );
    }
}
