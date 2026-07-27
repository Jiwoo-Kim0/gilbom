package com.gilbom.auth.my;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;

import com.gilbom.auth.audit.AuthenticationAuditService;
import com.gilbom.auth.my.MyAccountRepository.PasswordAccount;
import com.gilbom.auth.password.PasswordHashingService;
import com.gilbom.auth.session.AuthenticationCookieService;
import com.gilbom.auth.session.AuthenticationSessionService;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.common.web.RequestIdFilter;

class MyPasswordChangeServiceTest {

    private static final UUID USER_ID =
            UUID.fromString("28000000-0000-4000-8000-000000000001");
    private static final String CURRENT_PASSWORD =
            "current password";
    private static final String NEW_PASSWORD =
            "different password";
    private static final String CURRENT_HASH =
            "{argon2}$argon2id$current";
    private static final String NEW_HASH =
            "{argon2}$argon2id$new";

    private final CurrentAccountService currentAccountService =
            Mockito.mock(CurrentAccountService.class);
    private final MyAccountRepository accountRepository =
            Mockito.mock(MyAccountRepository.class);
    private final PasswordHashingService passwordHashingService =
            Mockito.mock(PasswordHashingService.class);
    private final AuthenticationAuditService auditService =
            Mockito.mock(AuthenticationAuditService.class);
    private final MyPasswordChangeCommitService commitService =
            Mockito.mock(MyPasswordChangeCommitService.class);
    private final AuthenticationSessionService sessionService =
            Mockito.mock(AuthenticationSessionService.class);
    private final AuthenticationCookieService cookieService =
            Mockito.mock(AuthenticationCookieService.class);
    private final MyPasswordChangeService service =
            new MyPasswordChangeService(
                    currentAccountService,
                    accountRepository,
                    passwordHashingService,
                    auditService,
                    commitService,
                    sessionService,
                    cookieService
            );
    private final Authentication authentication =
            Mockito.mock(Authentication.class);
    private final MockHttpServletRequest request = request();
    private final MockHttpServletResponse response =
            new MockHttpServletResponse();

    @Test
    void successHashesFirstThenRevokesAndExpiresEverySession() {
        allowAccount();
        when(passwordHashingService.verifyAgainstNullableHash(
                CURRENT_PASSWORD,
                CURRENT_HASH
        )).thenReturn(true);
        when(passwordHashingService.verifyAgainstNullableHash(
                NEW_PASSWORD,
                CURRENT_HASH
        )).thenReturn(false);
        when(passwordHashingService.hashNewPassword(NEW_PASSWORD))
                .thenReturn(NEW_HASH);
        when(commitService.commit(
                Mockito.eq(USER_ID),
                Mockito.eq(CURRENT_HASH),
                Mockito.eq(NEW_HASH),
                Mockito.any()
        )).thenReturn(true);

        service.change(
                new MyPasswordChangeRequest(
                        CURRENT_PASSWORD,
                        NEW_PASSWORD
                ),
                authentication,
                request,
                response
        );

        InOrder order = inOrder(
                passwordHashingService,
                commitService,
                sessionService,
                cookieService
        );
        order.verify(passwordHashingService)
                .hashNewPassword(NEW_PASSWORD);
        order.verify(commitService).commit(
                Mockito.eq(USER_ID),
                Mockito.eq(CURRENT_HASH),
                Mockito.eq(NEW_HASH),
                Mockito.any()
        );
        order.verify(sessionService)
                .expireCurrentClientSession(request);
        order.verify(cookieService).expire(response);
    }

    @Test
    void wrongCurrentPasswordUsesTheGeneric401AndDoesNotHash() {
        allowAccount();
        when(passwordHashingService.verifyAgainstNullableHash(
                CURRENT_PASSWORD,
                CURRENT_HASH
        )).thenReturn(false);

        assertThatThrownBy(() -> service.change(
                new MyPasswordChangeRequest(
                        CURRENT_PASSWORD,
                        NEW_PASSWORD
                ),
                authentication,
                request,
                response
        )).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.errorCode())
                    .isEqualTo(ErrorCode.AUTH_UNAUTHENTICATED);
            assertThat(exception.getMessage())
                    .isEqualTo("인증 정보가 올바르지 않습니다.");
        });

        verify(passwordHashingService, never())
                .hashNewPassword(Mockito.anyString());
        verify(auditService).recordPasswordChangeFailedRequired(
                Mockito.any(),
                Mockito.eq(USER_ID)
        );
    }

    @Test
    void currentPasswordCannotBeReused() {
        allowAccount();
        when(passwordHashingService.verifyAgainstNullableHash(
                CURRENT_PASSWORD,
                CURRENT_HASH
        )).thenReturn(true);
        when(passwordHashingService.verifyAgainstNullableHash(
                NEW_PASSWORD,
                CURRENT_HASH
        )).thenReturn(true);

        assertThatThrownBy(() -> service.change(
                new MyPasswordChangeRequest(
                        CURRENT_PASSWORD,
                        NEW_PASSWORD
                ),
                authentication,
                request,
                response
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.errorCode())
                        .isEqualTo(
                                ErrorCode.AUTH_PASSWORD_REUSE_NOT_ALLOWED
                        )
        );

        verify(commitService, never()).commit(
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()
        );
    }

    private void allowAccount() {
        when(currentAccountService.requireAuthenticatedSession(
                authentication,
                request
        )).thenReturn(USER_ID);
        when(accountRepository.findPasswordAccount(USER_ID))
                .thenReturn(Optional.of(new PasswordAccount(
                        USER_ID,
                        CURRENT_HASH
                )));
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request =
                new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.28");
        request.setAttribute(
                RequestIdFilter.ATTRIBUTE_NAME,
                UUID.fromString(
                        "28000000-0000-4000-8000-000000000002"
                ).toString()
        );
        return request;
    }
}
