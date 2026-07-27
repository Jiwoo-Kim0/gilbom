package com.gilbom.auth.logout;

import java.util.UUID;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import com.gilbom.auth.AuthenticationDefenseService;
import com.gilbom.auth.audit.AuthenticationRequestAuditContext;
import com.gilbom.auth.session.AuthenticationCookieService;
import com.gilbom.auth.session.AuthenticationSessionService;
import com.gilbom.auth.session.SessionSecurityState;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Service
public class AuthenticationLogoutService {

    private final AuthenticationSessionService sessionService;
    private final AuthenticationDefenseService defenseService;
    private final AuthenticationCookieService cookieService;

    public AuthenticationLogoutService(
            AuthenticationSessionService sessionService,
            AuthenticationDefenseService defenseService,
            AuthenticationCookieService cookieService
    ) {
        this.sessionService = sessionService;
        this.defenseService = defenseService;
        this.cookieService = cookieService;
    }

    public void logout(
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        SessionSecurityState state =
                sessionService.findCurrentState(request)
                        .orElseThrow(
                                AuthenticationLogoutService::unauthenticated
                        );
        UUID userId = state.userId();
        if (userId == null
                || (authentication != null
                    && authentication.isAuthenticated()
                    && !(authentication
                        instanceof AnonymousAuthenticationToken)
                    && !userId.toString().equals(authentication.getName()))) {
            throw unauthenticated();
        }

        AuthenticationRequestAuditContext auditContext =
                AuthenticationRequestAuditContext.from(request);
        defenseService.logout(
                request,
                userId,
                auditContext
        );
        cookieService.expire(response);
    }

    private static BusinessException unauthenticated() {
        return new BusinessException(ErrorCode.AUTH_UNAUTHENTICATED);
    }
}
