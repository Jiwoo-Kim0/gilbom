package com.gilbom.auth.my;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;

import com.gilbom.auth.AuthenticatedUserIdResolver;
import com.gilbom.auth.mfa.AuthenticationUserRepository;
import com.gilbom.auth.mfa.AuthenticationUserRepository.AuthenticationUser;
import com.gilbom.auth.mfa.RoleSummaryResponse;
import com.gilbom.auth.model.AuthenticationState;
import com.gilbom.auth.my.MyAccountRepository.ProfileIdentity;
import com.gilbom.auth.session.AuthenticationSessionService;
import com.gilbom.auth.session.SessionSecurityState;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

class CurrentAccountServiceTest {

    private static final UUID USER_ID =
            UUID.fromString("26000000-0000-4000-8000-000000000001");
    private static final Instant NOW =
            Instant.parse("2026-07-27T14:00:00Z");

    private final AuthenticatedUserIdResolver userIdResolver =
            Mockito.mock(AuthenticatedUserIdResolver.class);
    private final AuthenticationSessionService sessionService =
            Mockito.mock(AuthenticationSessionService.class);
    private final AuthenticationUserRepository userRepository =
            Mockito.mock(AuthenticationUserRepository.class);
    private final MyAccountRepository accountRepository =
            Mockito.mock(MyAccountRepository.class);
    private final CurrentAccountService service =
            new CurrentAccountService(
                    userIdResolver,
                    sessionService,
                    userRepository,
                    accountRepository
            );
    private final Authentication authentication =
            Mockito.mock(Authentication.class);
    private final MockHttpServletRequest request =
            new MockHttpServletRequest();

    @Test
    void profileContainsOnlySafeIdentityRoleAndMfaFields() {
        allowAuthenticatedSession();
        when(accountRepository.findActiveProfile(USER_ID))
                .thenReturn(Optional.of(new ProfileIdentity(
                        USER_ID,
                        "operator@example.com",
                        "운영자",
                        "관제팀",
                        "매니저",
                        "서울",
                        MfaProfileStatus.ENROLLED,
                        NOW.minusSeconds(3600),
                        NOW
                )));
        when(userRepository.findActiveUser(USER_ID))
                .thenReturn(Optional.of(user()));

        MyProfileResponse result =
                service.profile(authentication, request);

        assertThat(result.email()).isEqualTo("operator@example.com");
        assertThat(result.displayName()).isEqualTo("운영자");
        assertThat(result.roles()).extracting(
                RoleSummaryResponse::roleCode
        ).containsExactly("OPERATOR");
        assertThat(result.permissions()).containsExactly(
                "ROBOT_STATUS_VIEW"
        );
        assertThat(result.mfaStatus())
                .isEqualTo(MfaProfileStatus.ENROLLED);
    }

    @Test
    void aPreAuthenticationSessionCannotReadTheProfile() {
        when(userIdResolver.require(authentication)).thenReturn(USER_ID);
        when(sessionService.findCurrentState(request))
                .thenReturn(Optional.of(new SessionSecurityState(
                        USER_ID,
                        AuthenticationState.MFA_REQUIRED,
                        NOW.plusSeconds(300),
                        NOW,
                        NOW.plusSeconds(300),
                        0
                )));

        assertThatThrownBy(() ->
                service.profile(authentication, request))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.AUTH_UNAUTHENTICATED)
                );

        verify(accountRepository, never()).findActiveProfile(USER_ID);
    }

    private void allowAuthenticatedSession() {
        when(userIdResolver.require(authentication)).thenReturn(USER_ID);
        when(sessionService.findCurrentState(request))
                .thenReturn(Optional.of(new SessionSecurityState(
                        USER_ID,
                        AuthenticationState.AUTHENTICATED,
                        NOW.plusSeconds(3600),
                        null,
                        null,
                        0
                )));
    }

    private static AuthenticationUser user() {
        return new AuthenticationUser(
                USER_ID,
                "운영자",
                List.of(new RoleSummaryResponse(
                        "OPERATOR",
                        "운영자"
                )),
                List.of("ROBOT_STATUS_VIEW")
        );
    }
}
