package com.gilbom.auth.registration;

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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.common.web.GlobalExceptionHandler;
import com.gilbom.common.web.RequestIdFilter;
import com.gilbom.auth.session.SessionSecurityStateRepository;
import com.gilbom.security.config.SecurityConfig;
import com.gilbom.security.web.SecurityErrorResponseWriter;
import com.gilbom.security.web.SecurityHeadersFilter;

@WebMvcTest(controllers = PublicRegistrationController.class)
@Import({
        SecurityConfig.class,
        GlobalExceptionHandler.class,
        SecurityErrorResponseWriter.class,
        SecurityHeadersFilter.class,
        RequestIdFilter.class
})
class PublicRegistrationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PublicRegistrationService registrationService;

    @MockitoBean
    private SessionSecurityStateRepository sessionStateRepository;

    @Test
    void publicEndpointsStillRequireCsrf() throws Exception {
        mockMvc.perform(post("/api/v1/auth/email-availability")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user@example.com"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(header().string(
                        "Cache-Control",
                        containsString("no-store")
                ))
                .andExpect(jsonPath("$.code", is("AUTH_FORBIDDEN")));
    }

    @Test
    void checksAvailabilityWithoutCaching() throws Exception {
        when(registrationService.checkEmailAvailability("User@Example.COM"))
                .thenReturn(new EmailAvailabilityResponse(true));

        mockMvc.perform(post("/api/v1/auth/email-availability")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"User@Example.COM"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Cache-Control",
                        containsString("no-store")
                ))
                .andExpect(jsonPath("$.available", is(true)));
    }

    @Test
    void registersAsPendingAndDoesNotReturnAuthenticationData() throws Exception {
        when(registrationService.register(any(PublicRegistrationRequest.class)))
                .thenReturn(PublicRegistrationResponse.pendingApproval());

        mockMvc.perform(post("/api/v1/auth/registrations")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"user@example.com",
                                  "password":"exact password",
                                  "displayName":"길봄 사용자"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Cache-Control",
                        containsString("no-store")
                ))
                .andExpect(jsonPath("$.accountStatus", is("PENDING_APPROVAL")))
                .andExpect(jsonPath("$.nextAction", is("LOGIN")))
                .andExpect(jsonPath("$.sessionId").doesNotExist())
                .andExpect(jsonPath("$.csrfToken").doesNotExist())
                .andExpect(jsonPath("$.authenticated").doesNotExist());
    }

    @Test
    void validatesEmailAndPasswordWithoutReflectingPassword() throws Exception {
        mockMvc.perform(post("/api/v1/auth/registrations")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"not-an-email",
                                  "password":"short",
                                  "displayName":"길봄 사용자"
                                }
                """))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(
                        "Cache-Control",
                        containsString("no-store")
                ))
                .andExpect(jsonPath("$.code", is("COMMON_VALIDATION_FAILED")))
                .andExpect(jsonPath("$.details", hasSize(2)))
                .andExpect(jsonPath(
                        "$.details[?(@.field == 'password')].rejectedValue",
                        org.hamcrest.Matchers.everyItem(nullValue())
                ));
    }

    @Test
    void duplicateEmailUsesStable409() throws Exception {
        when(registrationService.register(any(PublicRegistrationRequest.class)))
                .thenThrow(new BusinessException(
                        ErrorCode.AUTH_EMAIL_ALREADY_REGISTERED
                ));

        mockMvc.perform(post("/api/v1/auth/registrations")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"user@example.com",
                                  "password":"exact password",
                                  "displayName":"길봄 사용자"
                                }
                """))
                .andExpect(status().isConflict())
                .andExpect(header().string(
                        "Cache-Control",
                        containsString("no-store")
                ))
                .andExpect(jsonPath(
                        "$.code",
                        is("AUTH_EMAIL_ALREADY_REGISTERED")
                ));
    }

    @Test
    void rejectsUnknownRegistrationFields() throws Exception {
        mockMvc.perform(post("/api/v1/auth/registrations")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"user@example.com",
                                  "password":"exact password",
                                  "displayName":"길봄 사용자",
                                  "role":"ADMIN"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(
                        "Cache-Control",
                        containsString("no-store")
                ))
                .andExpect(jsonPath(
                        "$.code",
                        is("COMMON_MALFORMED_JSON")
                ));
    }
}
