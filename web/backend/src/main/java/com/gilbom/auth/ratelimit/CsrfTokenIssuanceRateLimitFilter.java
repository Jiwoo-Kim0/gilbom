package com.gilbom.auth.ratelimit;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.session.web.http.SessionRepositoryFilter;
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

/**
 * 세션 없는 CSRF token 조회가 새 JDBC 세션을 무제한 생성하지 못하게 제한한다.
 *
 * 기존 세션이 있는 조회는 새 세션 row를 만들지 않으므로 버킷을 소비하지 않는다.
 * 이 구분으로 정상적인 새로고침·다중 탭 복구는 보호하면서 세션 저장소 고갈만 겨냥한다.
 */
// Spring Session이 cookie의 DB session을 request에 연결한 뒤 실행해야
// getSession(false)로 "신규 session을 만들 요청"만 정확히 구분할 수 있다.
@Order(SessionRepositoryFilter.DEFAULT_ORDER + 1)
public class CsrfTokenIssuanceRateLimitFilter extends OncePerRequestFilter {

    static final String CSRF_TOKEN_PATH = "/api/v1/auth/csrf-token";

    private static final Logger LOGGER =
            LoggerFactory.getLogger(CsrfTokenIssuanceRateLimitFilter.class);

    private final PublicAuthRateLimiter rateLimiter;
    private final AuthenticationAuditService auditService;
    private final SecurityErrorResponseWriter errorWriter;

    public CsrfTokenIssuanceRateLimitFilter(
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
        return !HttpMethod.GET.matches(request.getMethod())
                || !CSRF_TOKEN_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");

        if (request.getSession(false) != null) {
            filterChain.doFilter(request, response);
            return;
        }

        final RateLimitDecision decision;
        try {
            decision = rateLimiter.consume(
                    PublicAuthRateLimitType.CSRF_TOKEN_ISSUANCE,
                    request.getRemoteAddr()
            );
        } catch (DataAccessException exception) {
            LOGGER.error(
                    "CSRF token issuance rate-limit unavailable. requestId={}",
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
