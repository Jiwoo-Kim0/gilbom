package com.gilbom.auth.ratelimit;

import java.io.IOException;
import java.util.Map;

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

@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class PublicAuthRateLimitFilter extends OncePerRequestFilter {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(PublicAuthRateLimitFilter.class);
    private static final Map<String, PublicAuthRateLimitType> LIMITED_PATHS =
            Map.of(
                    "/api/v1/auth/email-availability",
                    PublicAuthRateLimitType.EMAIL_AVAILABILITY,
                    "/api/v1/auth/registrations",
                    PublicAuthRateLimitType.REGISTRATION
            );

    private final PublicAuthRateLimiter rateLimiter;
    private final AuthenticationAuditService auditService;
    private final SecurityErrorResponseWriter errorWriter;

    public PublicAuthRateLimitFilter(
            PublicAuthRateLimiter rateLimiter,
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
                || !LIMITED_PATHS.containsKey(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        PublicAuthRateLimitType limitType =
                LIMITED_PATHS.get(request.getRequestURI());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");

        final RateLimitDecision decision;
        try {
            decision = rateLimiter.consume(limitType, request.getRemoteAddr());
        } catch (DataAccessException exception) {
            LOGGER.error(
                    "Public authentication rate-limit unavailable. requestId={}",
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
