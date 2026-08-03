package com.gilbom.security;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gilbom.api.HealthController;
import com.gilbom.audit.SecurityAuditService;
import com.gilbom.auth.password.PasswordHashingConfiguration;
import com.gilbom.auth.model.AuthenticationState;
import com.gilbom.auth.session.SessionSecurityState;
import com.gilbom.auth.session.SessionSecurityStateRepository;
import com.gilbom.auth.session.AuthenticationSessionService;
import com.gilbom.auth.session.AuthenticationSessionStateController;
import com.gilbom.common.web.RequestIdFilter;
import com.gilbom.security.config.SecurityConfig;
import com.gilbom.security.web.CsrfTokenController;
import com.gilbom.security.web.SecurityErrorResponseWriter;
import com.gilbom.security.web.SecurityHeadersFilter;

@WebMvcTest(controllers = {
        HealthController.class,
        CsrfTokenController.class,
        AuthenticationSessionStateController.class,
        SecurityTestController.class
})
@Import({
        SecurityConfig.class,
        PasswordHashingConfiguration.class,
        SecurityErrorResponseWriter.class,
        SecurityHeadersFilter.class,
        RequestIdFilter.class
})
class SecurityConfigTest {

    private static final String REQUEST_ID = "e299ff0c-4a83-43c8-8212-ee1b537c5731";
    private static final UUID USER_ID =
            UUID.fromString("17000000-0000-4000-8000-000000000001");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private SessionSecurityStateRepository sessionStateRepository;

    @MockitoBean
    private AuthenticationSessionService authenticationSessionService;

    @MockitoBean
    private SecurityAuditService securityAuditService;

    @BeforeEach
    void setUpAuthenticatedSessionState() {
        when(sessionStateRepository.findBySessionId(anyString()))
                .thenReturn(Optional.of(new SessionSecurityState(
                        USER_ID,
                        AuthenticationState.AUTHENTICATED,
                        Instant.parse("2099-01-01T00:00:00Z"),
                        null,
                        null,
                        0
                )));
    }

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
    void everyResponseUsesTheBrowserSecurityHeaderBaseline() throws Exception {
        mockMvc.perform(get("/api/health").secure(true))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Content-Security-Policy",
                        SecurityHeadersFilter.CONTENT_SECURITY_POLICY))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string(
                        "Permissions-Policy",
                        SecurityHeadersFilter.PERMISSIONS_POLICY))
                .andExpect(header().string(
                        "Strict-Transport-Security",
                        "max-age=31536000"));
    }

    @Test
    void authenticatedRequestsCanReachProtectedEndpoints() throws Exception {
        mockMvc.perform(get("/test/security/protected")
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result", is("authenticated")));
    }

    @Test
    void everyPreAuthenticationStateIsRejectedByProtectedEndpoints()
            throws Exception {
        for (AuthenticationState state : java.util.List.of(
                AuthenticationState.PASSWORD_CHANGE_REQUIRED,
                AuthenticationState.MFA_ENROLLMENT_REQUIRED,
                AuthenticationState.MFA_REQUIRED
        )) {
            when(sessionStateRepository.findBySessionId(anyString()))
                    .thenReturn(Optional.of(new SessionSecurityState(
                            USER_ID,
                            state,
                            Instant.parse("2099-01-01T00:00:00Z"),
                            Instant.parse("2098-12-31T23:55:00Z"),
                            Instant.parse("2099-01-01T00:00:00Z"),
                            0
                    )));

            mockMvc.perform(get("/test/security/protected")
                            .session(new MockHttpSession())
                            .with(user(USER_ID.toString())))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath(
                            "$.code",
                            is("AUTH_UNAUTHENTICATED")
                    ));
        }
    }

    @Test
    void aRevokedServerSessionCannotBeReusedWithAStaleSecurityContext()
            throws Exception {
        when(sessionStateRepository.findBySessionId(anyString()))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/test/security/protected")
                        .session(new MockHttpSession())
                        .with(user(USER_ID.toString())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath(
                        "$.code",
                        is("AUTH_UNAUTHENTICATED")
                ));
    }

    @Test
    void stateChangingRequestsRequireSessionCsrfHeader() throws Exception {
        mockMvc.perform(post("/test/security/mutate")
                        .with(user(USER_ID.toString())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("AUTH_FORBIDDEN")))
                .andExpect(jsonPath("$.message", is("요청 검증에 실패했습니다.")));
        verify(securityAuditService).recordCsrfFailed(
                any(),
                any()
        );

        mockMvc.perform(post("/test/security/mutate")
                        .with(user(USER_ID.toString()))
                        .with(csrf().asHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result", is("changed")));
    }

    @Test
    void stateChangingRequestsRejectATamperedCsrfHeader() throws Exception {
        var issuedTokenResult =
                mockMvc.perform(get("/api/v1/auth/csrf-token"))
                        .andExpect(status().isOk())
                        .andReturn();
        MockHttpSession session = (MockHttpSession)
                issuedTokenResult.getRequest().getSession(false);
        String issuedToken = objectMapper.readTree(
                issuedTokenResult.getResponse().getContentAsString()
        ).get("token").asText();
        String tamperedToken =
                (issuedToken.startsWith("A") ? "B" : "A")
                        + issuedToken.substring(1);

        mockMvc.perform(post("/test/security/mutate")
                        .session(session)
                        .with(user(USER_ID.toString()))
                        .header("X-CSRF-TOKEN", tamperedToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("AUTH_FORBIDDEN")))
                .andExpect(jsonPath("$.message", is("요청 검증에 실패했습니다.")));

        verify(securityAuditService).recordCsrfFailed(any(), any());
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
    void crossOriginPreflightIsClosedByDefault() throws Exception {
        mockMvc.perform(options("/api/v1/auth/login")
                        .header("Origin", "https://admin.example.com")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(
                        "Access-Control-Allow-Origin"
                ));
    }

    @Test
    void anonymousClientCanRestoreItsCookieBoundAuthenticationState()
            throws Exception {
        when(authenticationSessionService.findCurrentState(
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/auth/session-state"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath(
                        "$.authenticationState",
                        is("ANONYMOUS")
                ))
                .andExpect(jsonPath("$.challengeExpiresAt").doesNotExist());
    }

    @Test
    void newPasswordHashesUseArgon2AndRemainSelfDescribing() {
        String encoded = passwordEncoder.encode("safe-test-password");

        assertThat(encoded)
                .startsWith("{argon2}$argon2id$")
                .contains("m=19456,t=2,p=1");
        assertThat(passwordEncoder.matches("safe-test-password", encoded)).isTrue();
    }

    @Test
    void passwordHashingUsesRandomSaltAndRejectsUnregisteredAlgorithms() {
        String first = passwordEncoder.encode("same-safe-password");
        String second = passwordEncoder.encode("same-safe-password");

        assertThat(first).isNotEqualTo(second);
        assertThat(passwordEncoder.matches("same-safe-password", first)).isTrue();
        assertThat(passwordEncoder.matches("same-safe-password", second)).isTrue();

        assertThatThrownBy(() -> passwordEncoder.matches(
                "safe-test-password",
                "{bcrypt}$2a$10$not-an-accepted-hash"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void methodSecurityUsesFineGrainedPermissions() throws Exception {
        mockMvc.perform(get("/test/security/admin")
                        .with(user(USER_ID.toString())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("AUTH_FORBIDDEN")));
        verify(securityAuditService).recordAccessDenied(
                any(),
                any()
        );

        mockMvc.perform(get("/test/security/admin")
                        .with(user(USER_ID.toString())
                        .authorities(new SimpleGrantedAuthority("ADMIN_SETTINGS_MANAGE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result", is("authorized")));
    }
}
