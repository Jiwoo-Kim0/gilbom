package com.gilbom.auth.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gilbom.auth.audit.AuthenticationAuditService;
import com.gilbom.security.web.SecurityErrorResponseWriter;

class AuthenticationRateLimitFilterTest {

    private final AuthenticationRateLimiter rateLimiter =
            Mockito.mock(AuthenticationRateLimiter.class);
    private final AuthenticationAuditService auditService =
            Mockito.mock(AuthenticationAuditService.class);
    private final ObjectMapper objectMapper =
            new ObjectMapper().findAndRegisterModules();
    private final AuthenticationRateLimitFilter filter =
            new AuthenticationRateLimitFilter(
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
                "POST",
                "/api/v1/auth/login"
        );
        request.setRemoteAddr("203.0.113.11");
        response = new MockHttpServletResponse();
        filterChain = new MockFilterChain();
    }

    @Test
    void blocksTheTwentyFirstAuthenticationRequest() throws Exception {
        when(rateLimiter.consumeIpAuthenticationRequest("203.0.113.11"))
                .thenReturn(new RateLimitDecision(false, 41, true));

        filter.doFilter(request, response, filterChain);

        JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("41");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("no-store");
        assertThat(body.get("code").asText())
                .isEqualTo("COMMON_RATE_LIMIT_EXCEEDED");
        verify(auditService).recordRateLimitedBestEffort(
                Mockito.any(),
                Mockito.isNull()
        );
        assertThat(filterChain.getRequest()).isNull();
    }

    @Test
    void databaseFailureFailsClosed() throws Exception {
        when(rateLimiter.consumeIpAuthenticationRequest("203.0.113.11"))
                .thenThrow(new DataAccessResourceFailureException(
                        "password=must-not-leak"
                ));

        filter.doFilter(request, response, filterChain);

        JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(body.get("code").asText()).isEqualTo("DB_UNAVAILABLE");
        assertThat(response.getContentAsString())
                .doesNotContain("must-not-leak");
        verify(auditService, never()).recordRateLimitedBestEffort(
                Mockito.any(),
                Mockito.any()
        );
    }

    @Test
    void repeatedRejectionDoesNotAmplifyTheAuditLog() throws Exception {
        when(rateLimiter.consumeIpAuthenticationRequest("203.0.113.11"))
                .thenReturn(new RateLimitDecision(false, 40, false));

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(429);
        verify(auditService, never()).recordRateLimitedBestEffort(
                Mockito.any(),
                Mockito.any()
        );
    }

    @Test
    void unrelatedAuthenticationPathDoesNotConsumeTheIpBucket()
            throws Exception {
        MockHttpServletRequest unrelated = new MockHttpServletRequest(
                "POST",
                "/api/v1/auth/registrations"
        );

        filter.doFilter(unrelated, response, filterChain);

        verify(rateLimiter, never())
                .consumeIpAuthenticationRequest(Mockito.anyString());
        assertThat(filterChain.getRequest()).isSameAs(unrelated);
    }

    @Test
    void initialPasswordChangeAlsoConsumesTheAuthenticationIpBucket()
            throws Exception {
        MockHttpServletRequest initialChange = new MockHttpServletRequest(
                "POST",
                "/api/v1/auth/password/initial-change"
        );
        initialChange.setRemoteAddr("203.0.113.11");
        when(rateLimiter.consumeIpAuthenticationRequest("203.0.113.11"))
                .thenReturn(new RateLimitDecision(true, 0));

        filter.doFilter(initialChange, response, filterChain);

        verify(rateLimiter)
                .consumeIpAuthenticationRequest("203.0.113.11");
        assertThat(filterChain.getRequest()).isSameAs(initialChange);
    }

    @Test
    void totpEnrollmentAlsoConsumesTheAuthenticationIpBucket()
            throws Exception {
        MockHttpServletRequest enrollment = new MockHttpServletRequest(
                "POST",
                "/api/v1/auth/mfa/totp/enroll"
        );
        enrollment.setRemoteAddr("203.0.113.11");
        when(rateLimiter.consumeIpAuthenticationRequest("203.0.113.11"))
                .thenReturn(new RateLimitDecision(true, 0));

        filter.doFilter(enrollment, response, filterChain);

        verify(rateLimiter)
                .consumeIpAuthenticationRequest("203.0.113.11");
        assertThat(filterChain.getRequest()).isSameAs(enrollment);
    }
}
