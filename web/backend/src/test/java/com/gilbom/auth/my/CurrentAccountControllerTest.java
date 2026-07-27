package com.gilbom.auth.my;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.gilbom.auth.logout.AuthenticationLogoutController;
import com.gilbom.auth.logout.AuthenticationLogoutService;
import com.gilbom.auth.mfa.AuthenticatedUserResponse;
import com.gilbom.auth.mfa.RoleSummaryResponse;
import com.gilbom.auth.model.AuthenticationState;
import com.gilbom.auth.session.SessionSecurityState;
import com.gilbom.auth.session.SessionSecurityStateRepository;
import com.gilbom.common.web.GlobalExceptionHandler;
import com.gilbom.common.web.RequestIdFilter;
import com.gilbom.security.config.SecurityConfig;
import com.gilbom.security.web.SecurityErrorResponseWriter;
import com.gilbom.security.web.SecurityHeadersFilter;

@WebMvcTest(controllers = {
        CurrentAccountController.class,
        AuthenticationLogoutController.class
})
@Import({
        SecurityConfig.class,
        GlobalExceptionHandler.class,
        SecurityErrorResponseWriter.class,
        SecurityHeadersFilter.class,
        RequestIdFilter.class
})
class CurrentAccountControllerTest {

    private static final UUID USER_ID =
            UUID.fromString("30000000-0000-4000-8000-000000000001");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CurrentAccountService accountService;

    @MockitoBean
    private MyPasswordChangeService passwordChangeService;

    @MockitoBean
    private AuthenticationLogoutService logoutService;

    @MockitoBean
    private SessionSecurityStateRepository sessionStateRepository;

    @BeforeEach
    void setUpAuthenticatedSessionState() {
        when(sessionStateRepository.findBySessionId(any()))
                .thenReturn(java.util.Optional.of(
                        new SessionSecurityState(
                                USER_ID,
                                AuthenticationState.AUTHENTICATED,
                                Instant.parse(
                                        "2099-01-01T00:00:00Z"
                                ),
                                null,
                                null,
                                0
                        )
                ));
    }

    @Test
    void currentUserAndProfileRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/my/profile"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void profileDoesNotExposeAuthenticationSecrets() throws Exception {
        when(accountService.profile(any(), any()))
                .thenReturn(new MyProfileResponse(
                        USER_ID,
                        "operator@example.com",
                        "운영자",
                        "관제팀",
                        null,
                        null,
                        List.of(new RoleSummaryResponse(
                                "OPERATOR",
                                "운영자"
                        )),
                        List.of("ROBOT_STATUS_VIEW"),
                        MfaProfileStatus.ENROLLED,
                        Instant.parse("2026-07-27T10:00:00Z"),
                        Instant.parse("2026-07-27T11:00:00Z")
                ));

        mockMvc.perform(get("/api/v1/my/profile")
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL,
                        containsString("no-store")
                ))
                .andExpect(jsonPath(
                        "$.email",
                        is("operator@example.com")
                ))
                .andExpect(jsonPath(
                        "$.mfaStatus",
                        is("ENROLLED")
                ))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.totpSecret").doesNotExist())
                .andExpect(jsonPath("$.recoveryCodes").doesNotExist())
                .andExpect(jsonPath("$.csrfToken").doesNotExist())
                .andExpect(jsonPath("$.sessionId").doesNotExist());
    }

    @Test
    void currentUserReturnsTheAuthorizationSnapshot() throws Exception {
        when(accountService.currentUser(any(), any()))
                .thenReturn(new AuthenticatedUserResponse(
                        USER_ID,
                        "운영자",
                        List.of(new RoleSummaryResponse(
                                "OPERATOR",
                                "운영자"
                        )),
                        List.of("ROBOT_STATUS_VIEW")
                ));

        mockMvc.perform(get("/api/v1/auth/me")
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId", is(USER_ID.toString())))
                .andExpect(jsonPath("$.permissions", hasSize(1)));
    }

    @Test
    void passwordChangeRequiresCsrfAndReturnsNoContent()
            throws Exception {
        String body = """
                {
                  "currentPassword":"current password",
                  "newPassword":"different password"
                }
                """;

        mockMvc.perform(patch("/api/v1/my/password")
                        .with(user(USER_ID.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/v1/my/password")
                        .with(user(USER_ID.toString()))
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent())
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL,
                        containsString("no-store")
                ));
    }

    @Test
    void invalidPasswordsAreNeverReflected() throws Exception {
        mockMvc.perform(patch("/api/v1/my/password")
                        .with(user(USER_ID.toString()))
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentPassword":"short",
                                  "newPassword":"tiny"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath(
                        "$.code",
                        is("COMMON_VALIDATION_FAILED")
                ))
                .andExpect(jsonPath("$.details", hasSize(2)))
                .andExpect(jsonPath(
                        "$.details[*].rejectedValue",
                        org.hamcrest.Matchers.everyItem(nullValue())
                ));
    }
}
