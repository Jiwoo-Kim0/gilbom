package com.gilbom.auth.mfa;

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
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.gilbom.auth.session.SessionSecurityStateRepository;
import com.gilbom.common.web.GlobalExceptionHandler;
import com.gilbom.common.web.RequestIdFilter;
import com.gilbom.security.config.SecurityConfig;
import com.gilbom.security.web.SecurityErrorResponseWriter;
import com.gilbom.security.web.SecurityHeadersFilter;

@WebMvcTest(controllers = MfaAuthenticationController.class)
@Import({
        SecurityConfig.class,
        GlobalExceptionHandler.class,
        SecurityErrorResponseWriter.class,
        SecurityHeadersFilter.class,
        RequestIdFilter.class
})
class MfaAuthenticationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MfaAuthenticationService authenticationService;

    @MockitoBean
    private SessionSecurityStateRepository sessionStateRepository;

    @Test
    void preAuthenticationMfaEndpointsStillRequireCsrf() throws Exception {
        mockMvc.perform(post("/api/v1/auth/mfa/totp/enroll"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("AUTH_FORBIDDEN")));
    }

    @Test
    void enrollmentResponseIsNeverCacheable() throws Exception {
        when(authenticationService.enroll(any()))
                .thenReturn(new TotpEnrollmentResponse(
                        "otpauth://totp/GILBOM:user?secret=ABC",
                        "ABC",
                        Instant.parse("2026-07-27T13:05:00Z")
                ));

        mockMvc.perform(post("/api/v1/auth/mfa/totp/enroll")
                        .with(csrf().asHeader()))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL,
                        containsString("no-store")
                ))
                .andExpect(jsonPath("$.manualEntryKey", is("ABC")));
    }

    @Test
    void invalidTotpDoesNotReflectTheSubmittedCode() throws Exception {
        mockMvc.perform(post("/api/v1/auth/mfa/totp/verify")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"1234567"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath(
                        "$.code",
                        is("COMMON_VALIDATION_FAILED")
                ))
                .andExpect(jsonPath(
                        "$.details[0].rejectedValue",
                        is(nullValue())
                ));
    }

    @Test
    void confirmationReturnsTenRecoveryCodesOnlyOnSuccess()
            throws Exception {
        List<String> codes = java.util.stream.IntStream
                .rangeClosed(1, 10)
                .mapToObj(index -> String.format(
                        java.util.Locale.ROOT,
                        "%02d-AAAA-AAAA-AAAA-AAAA",
                        index
                ))
                .toList();
        when(authenticationService.confirmEnrollment(
                any(TotpCodeRequest.class),
                any(),
                any()
        )).thenReturn(new MfaEnrollmentConfirmationResponse(
                new AuthenticatedUserResponse(
                        UUID.fromString(
                                "25000000-0000-4000-8000-000000000001"
                        ),
                        "운영자",
                        List.of(new RoleSummaryResponse(
                                "OPERATOR",
                                "운영자"
                        )),
                        List.of("ROBOT_READ")
                ),
                "replacement-csrf",
                Instant.parse("2026-07-27T21:00:00Z"),
                false,
                codes
        ));

        mockMvc.perform(post("/api/v1/auth/mfa/totp/confirm")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"123456"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL,
                        containsString("no-store")
                ))
                .andExpect(jsonPath(
                        "$.csrfToken",
                        is("replacement-csrf")
                ))
                .andExpect(jsonPath("$.recoveryCodes", hasSize(10)))
                .andExpect(jsonPath("$.sessionId").doesNotExist());
    }
}
