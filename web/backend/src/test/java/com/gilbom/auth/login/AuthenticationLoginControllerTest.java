package com.gilbom.auth.login;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.gilbom.auth.model.AuthenticationState;
import com.gilbom.auth.mfa.AuthenticationResponse;
import com.gilbom.auth.ratelimit.AuthenticationRateLimitExceededException;
import com.gilbom.auth.session.SessionSecurityStateRepository;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.common.web.GlobalExceptionHandler;
import com.gilbom.common.web.RequestIdFilter;
import com.gilbom.security.config.SecurityConfig;
import com.gilbom.security.web.SecurityErrorResponseWriter;
import com.gilbom.security.web.SecurityHeadersFilter;

@WebMvcTest(controllers = AuthenticationLoginController.class)
@Import({
        SecurityConfig.class,
        GlobalExceptionHandler.class,
        SecurityErrorResponseWriter.class,
        SecurityHeadersFilter.class,
        RequestIdFilter.class
})
class AuthenticationLoginControllerTest {

    private static final Instant CHALLENGE_EXPIRY =
            Instant.parse("2026-07-27T10:35:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthenticationLoginService loginService;

    @MockitoBean
    private InitialPasswordChangeService passwordChangeService;

    @MockitoBean
    private SessionSecurityStateRepository sessionStateRepository;

    @Test
    void loginRequiresCsrfEvenThoughItDoesNotRequireAuthentication()
            throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validLoginJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("AUTH_FORBIDDEN")));
    }

    @Test
    void passwordSuccessReturnsOnlyThePreAuthenticationState()
            throws Exception {
        when(loginService.login(
                any(LoginRequest.class),
                any(),
                any()
        )).thenReturn(new LoginChallengeResponse(
                AuthenticationState.MFA_REQUIRED,
                CHALLENGE_EXPIRY
        ));

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validLoginJson()))
                .andExpect(status().isAccepted())
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL,
                        containsString("no-store")
                ))
                .andExpect(jsonPath("$.loginState", is("MFA_REQUIRED")))
                .andExpect(jsonPath(
                        "$.challengeExpiresAt",
                        is("2026-07-27T10:35:00Z")
                ))
                .andExpect(jsonPath("$.authenticated").doesNotExist())
                .andExpect(jsonPath("$.sessionId").doesNotExist())
                .andExpect(jsonPath("$.csrfToken").doesNotExist());
    }

    @Test
    void mfaExemptPasswordSuccessReturnsTheAuthenticatedSession()
            throws Exception {
        when(loginService.login(
                any(LoginRequest.class),
                any(),
                any()
        )).thenReturn(new AuthenticationResponse(
                null,
                "replacement-csrf",
                CHALLENGE_EXPIRY.plusSeconds(3600),
                false
        ));

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validLoginJson()))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL,
                        containsString("no-store")
                ))
                .andExpect(jsonPath(
                        "$.csrfToken",
                        is("replacement-csrf")
                ))
                .andExpect(jsonPath("$.loginState").doesNotExist());
    }

    @Test
    void invalidInputNeverReflectsTheSubmittedPassword() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"not-an-email",
                                  "password":"short"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is(
                        "COMMON_VALIDATION_FAILED"
                )))
                .andExpect(jsonPath("$.details", hasSize(2)))
                .andExpect(jsonPath(
                        "$.details[?(@.field == 'password')].rejectedValue",
                        org.hamcrest.Matchers.everyItem(nullValue())
                ));
    }

    @Test
    void allCredentialFailuresUseTheStable401Contract() throws Exception {
        when(loginService.login(
                any(LoginRequest.class),
                any(),
                any()
        )).thenThrow(new BusinessException(
                ErrorCode.AUTH_UNAUTHENTICATED,
                "인증 정보가 올바르지 않습니다."
        ));

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validLoginJson()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("AUTH_UNAUTHENTICATED")))
                .andExpect(jsonPath(
                        "$.message",
                        is("인증 정보가 올바르지 않습니다.")
                ));
    }

    @Test
    void accountRateLimitReturnsRetryAfter() throws Exception {
        when(loginService.login(
                any(LoginRequest.class),
                any(),
                any()
        )).thenThrow(new AuthenticationRateLimitExceededException(37));

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validLoginJson()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "37"))
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL,
                        containsString("no-store")
                ))
                .andExpect(jsonPath(
                        "$.code",
                        is("COMMON_RATE_LIMIT_EXCEEDED")
                ));
    }

    @Test
    void initialPasswordChangeIsPublicToSpringButRestrictedByPreAuthService()
            throws Exception {
        when(passwordChangeService.change(
                any(InitialPasswordChangeRequest.class),
                any()
        )).thenReturn(new LoginChallengeResponse(
                AuthenticationState.MFA_ENROLLMENT_REQUIRED,
                CHALLENGE_EXPIRY
        ));

        mockMvc.perform(post("/api/v1/auth/password/initial-change")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword":"different password"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL,
                        containsString("no-store")
                ))
                .andExpect(jsonPath(
                        "$.loginState",
                        is("MFA_ENROLLMENT_REQUIRED")
                ));
    }

    @Test
    void passwordReuseHasAStable422Code() throws Exception {
        when(passwordChangeService.change(
                any(InitialPasswordChangeRequest.class),
                any()
        )).thenThrow(new BusinessException(
                ErrorCode.AUTH_PASSWORD_REUSE_NOT_ALLOWED
        ));

        mockMvc.perform(post("/api/v1/auth/password/initial-change")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword":"different password"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath(
                        "$.code",
                        is("AUTH_PASSWORD_REUSE_NOT_ALLOWED")
                ));
    }

    private static String validLoginJson() {
        return """
                {
                  "email":"user@example.com",
                  "password":"exact password"
                }
                """;
    }
}
