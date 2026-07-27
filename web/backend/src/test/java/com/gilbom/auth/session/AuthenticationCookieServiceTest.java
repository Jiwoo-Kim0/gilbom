package com.gilbom.auth.session;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthenticationCookieServiceTest {

    @Test
    void logoutCookieUsesTheSameScopeAndExpiresImmediately() {
        AuthenticationCookieService service =
                new AuthenticationCookieService(
                        "__Host-GILBOM_SESSION",
                        true,
                        true,
                        "Lax",
                        "/"
                );
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        service.expire(response);

        assertThat(response.getHeader(HttpHeaders.SET_COOKIE))
                .contains("__Host-GILBOM_SESSION=")
                .contains("Path=/")
                .contains("Max-Age=0")
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Lax")
                .doesNotContain("Domain=");
    }
}
