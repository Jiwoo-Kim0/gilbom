package com.gilbom.auth.ratelimit;

import java.io.IOException;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

import com.gilbom.auth.audit.AuthenticationAuditService;
import com.gilbom.auth.audit.AuthenticationRequestAuditContext;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.common.web.RequestIdFilter;
import com.gilbom.security.web.SecurityErrorResponseWriter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Order(Ordered.HIGHEST_PRECEDENCE + 31)
public class AuthenticationRateLimitFilter extends OncePerRequestFilter {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AuthenticationRateLimitFilter.class);
    private static final Set<String> LIMITED_PATHS = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/password/initial-change",
            "/api/v1/auth/mfa/totp/enroll",
            "/api/v1/auth/mfa/totp/confirm",
            "/api/v1/auth/mfa/totp/verify",
            "/api/v1/auth/mfa/recovery"
    );

    private final AuthenticationRateLimiter rateLimiter;
    private final AuthenticationAuditService auditService;
    private final SecurityErrorResponseWriter errorWriter;

    public AuthenticationRateLimitFilter(
            AuthenticationRateLimiter rateLimiter,
            AuthenticationAuditService auditService,
            SecurityErrorResponseWriter errorWriter
    ) {
        this.rateLimiter = rateLimiter;
        this.auditService = auditService;
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod())
                || !LIMITED_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");

        final RateLimitDecision decision;
        try {
            decision = rateLimiter.consumeIpAuthenticationRequest(
                    request.getRemoteAddr()
            );
        } catch (DataAccessException exception) {
            LOGGER.error(
                    "Authentication IP rate-limit unavailable. requestId={}",
                    RequestIdFilter.getOrCreateRequestId(request)
            );
            errorWriter.write(request, response, ErrorCode.DB_UNAVAILABLE);
            return;
        }

        if (!decision.allowed()) {
            if (decision.firstRejectedRequest()) {
                auditService.recordRateLimitedBestEffort(
                        AuthenticationRequestAuditContext.from(request),
                        null
                );
            }
            response.setHeader(
                    HttpHeaders.RETRY_AFTER,
                    Long.toString(decision.retryAfterSeconds())
            );
            errorWriter.write(
                    request,
                    response,
                    ErrorCode.COMMON_RATE_LIMIT_EXCEEDED,
                    "잠시 후 다시 시도해 주세요."
            );
            return;
        }

        filterChain.doFilter(request, response);
    }
}
