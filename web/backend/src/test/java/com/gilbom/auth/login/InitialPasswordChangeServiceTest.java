package com.gilbom.auth.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;

import com.gilbom.auth.account.AccountAuthenticationState;
import com.gilbom.auth.account.AccountStatus;
import com.gilbom.auth.audit.AuthenticationAuditService;
import com.gilbom.auth.login.LoginAccountRepository.LoginAccount;
import com.gilbom.auth.model.AuthenticationState;
import com.gilbom.auth.password.PasswordHashingService;
import com.gilbom.auth.session.AuthenticationSessionService;
import com.gilbom.auth.session.AuthenticationSessionService.ChallengeSession;
import com.gilbom.auth.session.SessionSecurityState;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.common.web.RequestIdFilter;

class InitialPasswordChangeServiceTest {

    private static final UUID USER_ID =
            UUID.fromString("23000000-0000-4000-8000-000000000001");
    private static final UUID REQUEST_ID =
            UUID.fromString("23000000-0000-4000-8000-000000000002");
    private static final Instant NOW =
            Instant.parse("2026-07-27T09:30:00Z");
    private static final String CURRENT_HASH =
            "{argon2}$argon2id$current";
    private static final String NEW_HASH =
            "{argon2}$argon2id$new";
    private static final String NEW_PASSWORD = "different password";

    private final LoginAccountRepository accountRepository =
            Mockito.mock(LoginAccountRepository.class);
    private final PasswordHashingService passwordHashingService =
            Mockito.mock(PasswordHashingService.class);
    private final AuthenticationSessionService sessionService =
            Mockito.mock(AuthenticationSessionService.class);
    private final AuthenticationAuditService auditService =
            Mockito.mock(AuthenticationAuditService.class);
    private final InitialPasswordChangeCommitService commitService =
            Mockito.mock(InitialPasswordChangeCommitService.class);
    private final InitialPasswordChangeService service =
            new InitialPasswordChangeService(
                    accountRepository,
                    passwordHashingService,
                    sessionService,
                    auditService,
                    commitService
            );
    private final MockHttpServletRequest request = request();

    @Test
    void changesOnlyFromPasswordChangeRequiredAndReturnsMfaEnrollment() {
        stubUsableAccount();
        when(passwordHashingService.verifyAgainstNullableHash(
                NEW_PASSWORD,
                CURRENT_HASH
        )).thenReturn(false);
        when(passwordHashingService.hashNewPassword(NEW_PASSWORD))
                .thenReturn(NEW_HASH);
        when(commitService.commit(
                Mockito.eq(request),
                Mockito.eq(USER_ID),
                Mockito.eq(CURRENT_HASH),
                Mockito.eq(NEW_HASH),
                Mockito.any()
        )).thenReturn(Optional.of(new ChallengeSession(
                AuthenticationState.MFA_ENROLLMENT_REQUIRED,
                NOW.plusSeconds(300)
        )));

        LoginChallengeResponse response = service.change(
                new InitialPasswordChangeRequest(NEW_PASSWORD),
                request
        );

        assertThat(response.loginState())
                .isEqualTo(AuthenticationState.MFA_ENROLLMENT_REQUIRED);
        verify(commitService).commit(
                Mockito.eq(request),
                Mockito.eq(USER_ID),
                Mockito.eq(CURRENT_HASH),
                Mockito.eq(NEW_HASH),
                Mockito.any()
        );
    }

    @Test
    void samePasswordIsRejectedAndAuditedWithoutCreatingANewHash() {
        stubUsableAccount();
        when(passwordHashingService.verifyAgainstNullableHash(
                NEW_PASSWORD,
                CURRENT_HASH
        )).thenReturn(true);

        assertThatThrownBy(() -> service.change(
                new InitialPasswordChangeRequest(NEW_PASSWORD),
                request
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.errorCode())
                        .isEqualTo(
                                ErrorCode.AUTH_PASSWORD_REUSE_NOT_ALLOWED
                        ));

        verify(auditService)
                .recordInitialPasswordChangeFailedRequired(
                        Mockito.any(),
                        Mockito.eq(USER_ID)
                );
        verify(passwordHashingService, never())
                .hashNewPassword(Mockito.any());
        verifyNoCommit();
    }

    @Test
    void missingPreAuthenticationChallengeUsesTheSame401AndAudits() {
        when(sessionService.findCurrentState(request))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.change(
                new InitialPasswordChangeRequest(NEW_PASSWORD),
                request
        )).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.errorCode())
                    .isEqualTo(ErrorCode.AUTH_UNAUTHENTICATED);
            assertThat(exception.getMessage())
                    .isEqualTo("인증 정보가 올바르지 않습니다.");
        });

        verify(auditService)
                .recordInitialPasswordChangeFailedRequired(
                        Mockito.any(),
                        Mockito.isNull()
                );
        verifyNoCommit();
    }

    @Test
    void unsupportedCurrentHashFailsClosedAs401AndAudits() {
        stubUsableAccount();
        when(passwordHashingService.verifyAgainstNullableHash(
                NEW_PASSWORD,
                CURRENT_HASH
        )).thenThrow(new IllegalArgumentException("unsupported"));

        assertThatThrownBy(() -> service.change(
                new InitialPasswordChangeRequest(NEW_PASSWORD),
                request
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.errorCode())
                        .isEqualTo(ErrorCode.AUTH_UNAUTHENTICATED));

        verify(auditService)
                .recordInitialPasswordChangeFailedRequired(
                        Mockito.any(),
                        Mockito.eq(USER_ID)
                );
        verifyNoCommit();
    }

    private void stubUsableAccount() {
        when(sessionService.findCurrentState(request))
                .thenReturn(Optional.of(new SessionSecurityState(
                        USER_ID,
                        AuthenticationState.PASSWORD_CHANGE_REQUIRED,
                        NOW.plusSeconds(300),
                        NOW,
                        NOW.plusSeconds(300),
                        0
                )));
        when(accountRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(new LoginAccount(
                        USER_ID,
                        CURRENT_HASH,
                        new AccountAuthenticationState(
                                AccountStatus.ACTIVE,
                                0,
                                null,
                                null
                        ),
                        true,
                        false
                )));
    }

    private void verifyNoCommit() {
        verify(commitService, never()).commit(
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()
        );
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.23");
        request.setAttribute(
                RequestIdFilter.ATTRIBUTE_NAME,
                REQUEST_ID.toString()
        );
        return request;
    }
}
