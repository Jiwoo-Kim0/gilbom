package com.gilbom.security;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.gilbom.api.HealthController;
import com.gilbom.common.web.RequestIdFilter;
import com.gilbom.security.config.SecurityConfig;
import com.gilbom.security.web.CsrfTokenController;
import com.gilbom.security.web.SecurityErrorResponseWriter;

@WebMvcTest(controllers = {
        HealthController.class,
        CsrfTokenController.class,
        SecurityTestController.class
})
@Import({SecurityConfig.class, SecurityErrorResponseWriter.class, RequestIdFilter.class})
class SecurityConfigTest {

    private static final String REQUEST_ID = "e299ff0c-4a83-43c8-8212-ee1b537c5731";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void healthEndpointIsPublicButOtherEndpointsAreDeniedByDefault() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/test/security/protected")
                        .header(RequestIdFilter.HEADER_NAME, REQUEST_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(RequestIdFilter.HEADER_NAME, REQUEST_ID))
                .andExpect(jsonPath("$.status", is(401)))
                .andExpect(jsonPath("$.code", is("AUTH_UNAUTHENTICATED")))
                .andExpect(jsonPath("$.requestId", is(REQUEST_ID)))
                .andExpect(jsonPath("$.details", hasSize(0)));
    }

    @Test
    void authenticatedRequestsCanReachProtectedEndpoints() throws Exception {
        mockMvc.perform(get("/test/security/protected").with(user("operator")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result", is("authenticated")));
    }

    @Test
    void stateChangingRequestsRequireSessionCsrfHeader() throws Exception {
        mockMvc.perform(post("/test/security/mutate").with(user("operator")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("AUTH_FORBIDDEN")))
                .andExpect(jsonPath("$.message", is("요청 검증에 실패했습니다.")));

        mockMvc.perform(post("/test/security/mutate")
                        .with(user("operator"))
                        .with(csrf().asHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result", is("changed")));
    }

    @Test
    void csrfEndpointCreatesANonCacheableSessionToken() throws Exception {
        mockMvc.perform(get("/api/v1/auth/csrf-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.headerName", is("X-CSRF-TOKEN")))
                .andExpect(jsonPath("$.token", not(blankOrNullString())));
    }

    @Test
    void newPasswordHashesUseArgon2AndRemainSelfDescribing() {
        String encoded = passwordEncoder.encode("safe-test-password");

        org.assertj.core.api.Assertions.assertThat(encoded).startsWith("{argon2}");
        org.assertj.core.api.Assertions.assertThat(
                passwordEncoder.matches("safe-test-password", encoded)).isTrue();
    }

    @Test
    void methodSecurityUsesFineGrainedPermissions() throws Exception {
        mockMvc.perform(get("/test/security/admin").with(user("manager")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("AUTH_FORBIDDEN")));

        mockMvc.perform(get("/test/security/admin").with(user("admin")
                        .authorities(new SimpleGrantedAuthority("ADMIN_SETTINGS_MANAGE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result", is("authorized")));
    }
}
