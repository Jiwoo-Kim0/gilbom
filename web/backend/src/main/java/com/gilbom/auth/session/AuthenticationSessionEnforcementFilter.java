package com.gilbom.auth.session;

import java.io.IOException;
import java.time.Clock;
import java.util.Optional;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.gilbom.auth.model.AuthenticationState;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.security.web.SecurityErrorResponseWriter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

public class AuthenticationSessionEnforcementFilter
        extends OncePerRequestFilter {

    private final SessionSecurityStateRepository repository;
    private final SecurityErrorResponseWriter errorWriter;
    private final Clock clock;

    public AuthenticationSessionEnforcementFilter(
            SessionSecurityStateRepository repository,
            SecurityErrorResponseWriter errorWriter,
            Clock clock
    ) {
        this.repository = repository;
        this.errorWriter = errorWriter;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session == null) {
            filterChain.doFilter(request, response);
            return;
        }

        final Optional<SessionSecurityState> optionalState;
        try {
            optionalState = repository.findBySessionId(session.getId());
        } catch (DataAccessException exception) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            errorWriter.write(request, response, ErrorCode.DB_UNAVAILABLE);
            return;
        }

        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();
        boolean hasAuthentication = isAuthenticated(authentication);

        if (optionalState.isEmpty()) {
            if (hasAuthentication) {
                rejectAndInvalidate(
                        request,
                        response,
                        session,
                        ErrorCode.AUTH_UNAUTHENTICATED
                );
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        SessionSecurityState state = optionalState.orElseThrow();
        boolean stateDoesNotMatchAuthentication =
                (hasAuthentication
                        && (state.authenticationState()
                            != AuthenticationState.AUTHENTICATED
                        || !String.valueOf(state.userId()).equals(
                            authentication.getName())))
                || (!hasAuthentication
                        && state.authenticationState()
                            == AuthenticationState.AUTHENTICATED);
        if (state.isExpiredAt(clock.instant())
                || stateDoesNotMatchAuthentication) {
            rejectAndInvalidate(
                    request,
                    response,
                    session,
                    ErrorCode.AUTH_UNAUTHENTICATED
            );
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void rejectAndInvalidate(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpSession session,
            ErrorCode errorCode
    ) throws IOException {
        String sessionId = session.getId();
        try {
            repository.deleteBySessionId(sessionId);
        } catch (DataAccessException ignored) {
            // 응답은 고정하고 DB 예외 세부정보를 로그나 본문에 노출하지 않는다.
        }
        try {
            session.invalidate();
        } catch (IllegalStateException ignored) {
            // 이미 폐기된 경우에도 동일한 401만 반환한다.
        }
        SecurityContextHolder.clearContext();
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        errorWriter.write(request, response, errorCode);
    }

    private static boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
