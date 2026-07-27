package com.gilbom.auth.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class AuthenticationRequestAuditContextTest {

    @Test
    void extractsOnlyRequestIdIpAndSanitizedUserAgent() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("203.0.113.12");
        request.addHeader("User-Agent", "safe-agent\r\ninjected");
        request.addHeader("Cookie", "GILBOM_SESSION=must-not-be-copied");
        request.setContent(
                "{\"password\":\"must-not-be-copied\"}".getBytes(
                        java.nio.charset.StandardCharsets.UTF_8
                )
        );

        AuthenticationRequestAuditContext context =
                AuthenticationRequestAuditContext.from(request);

        assertThat(context.ipAddress()).isEqualTo("203.0.113.12");
        assertThat(context.userAgent()).isEqualTo("safe-agentinjected");
        assertThat(context.toString())
                .doesNotContain("must-not-be-copied")
                .doesNotContain("GILBOM_SESSION");
    }

    @Test
    void discardsAnUnexpectedRemoteAddressInsteadOfStoringIt() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("attacker.example/password=value");

        AuthenticationRequestAuditContext context =
                AuthenticationRequestAuditContext.from(request);

        assertThat(context.ipAddress()).isNull();
    }
}
