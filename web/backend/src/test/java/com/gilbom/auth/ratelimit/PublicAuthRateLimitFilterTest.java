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

class PublicAuthRateLimitFilterTest {

    private final PublicAuthRateLimiter rateLimiter =
            Mockito.mock(PublicAuthRateLimiter.class);
    private final AuthenticationAuditService auditService =
            Mockito.mock(AuthenticationAuditService.class);
    private final ObjectMapper objectMapper =
            new ObjectMapper().findAndRegisterModules();
    private final PublicAuthRateLimitFilter filter =
            new PublicAuthRateLimitFilter(
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
                "/api/v1/auth/email-availability"
        );
        request.setRemoteAddr("203.0.113.10");
        response = new MockHttpServletResponse();
        filterChain = new MockFilterChain();
    }

    @Test
    void blockedRequestUsesCommon429AndRetryAfter() throws Exception {
        when(rateLimiter.consume(
                PublicAuthRateLimitType.EMAIL_AVAILABILITY,
                "203.0.113.10"
        )).thenReturn(new RateLimitDecision(false, 37, true));

        filter.doFilter(request, response, filterChain);

        JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("37");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(body.get("code").asText())
                .isEqualTo("COMMON_RATE_LIMIT_EXCEEDED");
        assertThat(filterChain.getRequest()).isNull();
        verify(auditService).recordRateLimitedBestEffort(
                Mockito.any(),
                Mockito.isNull()
        );
    }

    @Test
    void allowedRequestContinuesWithNoStore() throws Exception {
        when(rateLimiter.consume(
                PublicAuthRateLimitType.EMAIL_AVAILABILITY,
                "203.0.113.10"
        )).thenReturn(new RateLimitDecision(true, 0));

        filter.doFilter(request, response, filterChain);

        assertThat(filterChain.getRequest()).isSameAs(request);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
    }

    @Test
    void databaseFailureFailsClosedWithoutInternalMessage() throws Exception {
        when(rateLimiter.consume(
                PublicAuthRateLimitType.EMAIL_AVAILABILITY,
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
    }

    @Test
    void unrelatedPathDoesNotConsumeABucket() throws Exception {
        MockHttpServletRequest unrelated =
                new MockHttpServletRequest("POST", "/api/v1/auth/login");

        filter.doFilter(unrelated, response, filterChain);

        verify(rateLimiter, never()).consume(
                Mockito.any(),
                Mockito.anyString()
        );
        assertThat(filterChain.getRequest()).isSameAs(unrelated);
    }
}
