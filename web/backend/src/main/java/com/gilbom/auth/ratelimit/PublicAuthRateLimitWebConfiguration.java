package com.gilbom.auth.ratelimit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.gilbom.auth.audit.AuthenticationAuditService;
import com.gilbom.security.web.SecurityErrorResponseWriter;

@Configuration(proxyBeanMethods = false)
public class PublicAuthRateLimitWebConfiguration {

    @Bean
    public CsrfTokenIssuanceRateLimitFilter csrfTokenIssuanceRateLimitFilter(
            PublicAuthRateLimiter rateLimiter,
            AuthenticationAuditService auditService,
            SecurityErrorResponseWriter errorWriter
    ) {
        return new CsrfTokenIssuanceRateLimitFilter(
                rateLimiter,
                auditService,
                errorWriter
        );
    }

    @Bean
    public PublicAuthRateLimitFilter publicAuthRateLimitFilter(
            PublicAuthRateLimiter rateLimiter,
            AuthenticationAuditService auditService,
            SecurityErrorResponseWriter errorWriter
    ) {
        return new PublicAuthRateLimitFilter(
                rateLimiter,
                auditService,
                errorWriter
        );
    }

    @Bean
    public AuthenticationRateLimitFilter authenticationRateLimitFilter(
            AuthenticationRateLimiter rateLimiter,
            AuthenticationAuditService auditService,
            SecurityErrorResponseWriter errorWriter
    ) {
        return new AuthenticationRateLimitFilter(
                rateLimiter,
                auditService,
                errorWriter
        );
    }
}
