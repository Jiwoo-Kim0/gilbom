package com.gilbom.auth.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.web.http.SessionRepositoryFilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gilbom.auth.audit.AuthenticationAuditService;
import com.gilbom.security.web.SecurityErrorResponseWriter;

class CsrfTokenIssuanceRateLimitFilterTest {

    private final PublicAuthRateLimiter rateLimiter =
            Mockito.mock(PublicAuthRateLimiter.class);
    private final AuthenticationAuditService auditService =
            Mockito.mock(AuthenticationAuditService.class);
    private final ObjectMapper objectMapper =
            new ObjectMapper().findAndRegisterModules();
    private final CsrfTokenIssuanceRateLimitFilter filter =
            new CsrfTokenIssuanceRateLimitFilter(
                    rateLimiter,
                    auditService,
                    new SecurityErrorResponseWriter(objectMapper)
            );

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private MockFilterChain filterChain;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest(
                "GET",
                CsrfTokenIssuanceRateLimitFilter.CSRF_TOKEN_PATH
        );
        request.setRemoteAddr("203.0.113.10");
        response = new MockHttpServletResponse();
        filterChain = new MockFilterChain();
    }

    @Test
    void runsAfterSpringSessionSoExistingSessionsCanBeDetected() {
        Order order = CsrfTokenIssuanceRateLimitFilter.class
                .getAnnotation(Order.class);

        assertThat(order.value())
                .isEqualTo(SessionRepositoryFilter.DEFAULT_ORDER + 1);
    }

    @Test
    void sessionlessRequestConsumesTheDedicatedBucket() throws Exception {
        when(rateLimiter.consume(
                PublicAuthRateLimitType.CSRF_TOKEN_ISSUANCE,
                "203.0.113.10"
        )).thenReturn(new RateLimitDecision(true, 0));

        filter.doFilter(request, response, filterChain);

        assertThat(filterChain.getRequest()).isSameAs(request);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("no-store");
        verify(rateLimiter).consume(
                PublicAuthRateLimitType.CSRF_TOKEN_ISSUANCE,
                "203.0.113.10"
        );
    }

    @Test
    void existingSessionDoesNotConsumeTheCreationBucket() throws Exception {
        request.getSession();

        filter.doFilter(request, response, filterChain);

        assertThat(filterChain.getRequest()).isSameAs(request);
        verify(rateLimiter, never()).consume(
                Mockito.any(),
                Mockito.anyString()
        );
    }

    @Test
    void blockedRequestReturnsCommon429AndRetryAfter() throws Exception {
        when(rateLimiter.consume(
                PublicAuthRateLimitType.CSRF_TOKEN_ISSUANCE,
                "203.0.113.10"
        )).thenReturn(new RateLimitDecision(false, 19, true));

        filter.doFilter(request, response, filterChain);

        JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("19");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("no-store");
        assertThat(body.get("code").asText())
                .isEqualTo("COMMON_RATE_LIMIT_EXCEEDED");
        assertThat(filterChain.getRequest()).isNull();
        verify(auditService).recordRateLimitedBestEffort(
                Mockito.any(),
                Mockito.isNull()
        );
    }

    @Test
    void databaseFailureFailsClosedWithoutLeakingDetails() throws Exception {
        when(rateLimiter.consume(
                PublicAuthRateLimitType.CSRF_TOKEN_ISSUANCE,
                "203.0.113.10"
        )).thenThrow(new DataAccessResourceFailureException(
                "password=must-not-leak; SELECT subject_key_hash"
        ));

        filter.doFilter(request, response, filterChain);

        JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(body.get("code").asText()).isEqualTo("DB_UNAVAILABLE");
        assertThat(response.getContentAsString())
                .doesNotContain("must-not-leak")
                .doesNotContain("subject_key_hash");
        assertThat(filterChain.getRequest()).isNull();
    }

    @Test
    void unrelatedOrWrongMethodRequestDoesNotConsumeTheBucket()
            throws Exception {
        MockHttpServletRequest unrelated =
                new MockHttpServletRequest("GET", "/api/v1/auth/session-state");
        MockFilterChain unrelatedChain = new MockFilterChain();

        filter.doFilter(unrelated, response, unrelatedChain);

        MockHttpServletRequest wrongMethod = new MockHttpServletRequest(
                "POST",
                CsrfTokenIssuanceRateLimitFilter.CSRF_TOKEN_PATH
        );
        MockFilterChain wrongMethodChain = new MockFilterChain();
        filter.doFilter(
                wrongMethod,
                new MockHttpServletResponse(),
                wrongMethodChain
        );

        verify(rateLimiter, never()).consume(
                Mockito.any(),
                Mockito.anyString()
        );
        assertThat(unrelatedChain.getRequest()).isSameAs(unrelated);
        assertThat(wrongMethodChain.getRequest()).isSameAs(wrongMethod);
    }
}
