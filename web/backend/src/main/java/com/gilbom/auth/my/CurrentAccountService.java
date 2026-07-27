package com.gilbom.auth.my;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gilbom.auth.AuthenticatedUserIdResolver;
import com.gilbom.auth.mfa.AuthenticatedUserResponse;
import com.gilbom.auth.mfa.AuthenticationUserRepository;
import com.gilbom.auth.mfa.AuthenticationUserRepository.AuthenticationUser;
import com.gilbom.auth.my.MyAccountRepository.ProfileIdentity;
import com.gilbom.auth.session.AuthenticationSessionService;
import com.gilbom.auth.session.SessionSecurityState;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;

@Service
public class CurrentAccountService {

    private final AuthenticatedUserIdResolver userIdResolver;
    private final AuthenticationSessionService sessionService;
    private final AuthenticationUserRepository userRepository;
    private final MyAccountRepository accountRepository;

    public CurrentAccountService(
            AuthenticatedUserIdResolver userIdResolver,
            AuthenticationSessionService sessionService,
            AuthenticationUserRepository userRepository,
            MyAccountRepository accountRepository
    ) {
        this.userIdResolver = userIdResolver;
        this.sessionService = sessionService;
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
    }

    @Transactional(readOnly = true)
    public AuthenticatedUserResponse currentUser(
            Authentication authentication,
            HttpServletRequest request
    ) {
        UUID userId = requireAuthenticatedSession(
                authentication,
                request
        );
        return userRepository.findActiveUser(userId)
                .map(AuthenticationUser::toResponse)
                .orElseThrow(CurrentAccountService::unauthenticated);
    }

    @Transactional(readOnly = true)
    public MyProfileResponse profile(
            Authentication authentication,
            HttpServletRequest request
    ) {
        UUID userId = requireAuthenticatedSession(
                authentication,
                request
        );
        ProfileIdentity profile = accountRepository
                .findActiveProfile(userId)
                .orElseThrow(CurrentAccountService::unauthenticated);
        AuthenticationUser user = userRepository.findActiveUser(userId)
                .orElseThrow(CurrentAccountService::unauthenticated);
        return new MyProfileResponse(
                profile.userId(),
                profile.email(),
                profile.displayName(),
                profile.departmentName(),
                profile.jobTitle(),
                profile.officeName(),
                user.roles(),
                user.permissions(),
                profile.mfaStatus(),
                profile.passwordChangedAt(),
                profile.lastLoginAt()
        );
    }

    UUID requireAuthenticatedSession(
            Authentication authentication,
            HttpServletRequest request
    ) {
        UUID userId = userIdResolver.require(authentication);
        SessionSecurityState state =
                sessionService.findCurrentState(request)
                        .orElseThrow(CurrentAccountService::unauthenticated);
        if (!state.belongsTo(userId)
                || !state.authenticationState().isFullyAuthenticated()) {
            throw unauthenticated();
        }
        return userId;
    }

    private static BusinessException unauthenticated() {
        return new BusinessException(ErrorCode.AUTH_UNAUTHENTICATED);
    }
}
