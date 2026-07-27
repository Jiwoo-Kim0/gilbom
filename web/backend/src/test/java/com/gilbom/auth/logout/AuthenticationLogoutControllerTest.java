package com.gilbom.auth.logout;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.gilbom.auth.model.AuthenticationState;
import com.gilbom.auth.session.SessionSecurityState;
import com.gilbom.auth.session.SessionSecurityStateRepository;
import com.gilbom.common.web.GlobalExceptionHandler;
import com.gilbom.common.web.RequestIdFilter;
import com.gilbom.security.config.SecurityConfig;
import com.gilbom.security.web.SecurityErrorResponseWriter;
import com.gilbom.security.web.SecurityHeadersFilter;

@WebMvcTest(controllers = AuthenticationLogoutController.class)
@Import({
        SecurityConfig.class,
        GlobalExceptionHandler.class,
        SecurityErrorResponseWriter.class,
        SecurityHeadersFilter.class,
        RequestIdFilter.class
})
class AuthenticationLogoutControllerTest {

    private static final UUID USER_ID =
            UUID.fromString("31000000-0000-4000-8000-000000000001");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthenticationLogoutService logoutService;

    @MockitoBean
    private SessionSecurityStateRepository sessionStateRepository;

    @BeforeEach
    void setUpPreAuthenticationState() {
        when(sessionStateRepository.findBySessionId(any()))
                .thenReturn(Optional.of(new SessionSecurityState(
                        USER_ID,
                        AuthenticationState.MFA_REQUIRED,
                        Instant.parse("2099-01-01T00:00:00Z"),
                        Instant.parse("2098-12-31T23:55:00Z"),
                        Instant.parse("2099-01-01T00:00:00Z"),
                        0
                )));
    }

    @Test
    void logoutRequiresCsrfEvenDuringPreAuthentication()
            throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isForbidden());
    }

    @Test
    void logoutExpiresTheCookieAndReturnsNoContent()
            throws Exception {
        doAnswer(invocation -> {
            jakarta.servlet.http.HttpServletResponse response =
                    invocation.getArgument(2);
            response.addHeader(
                    HttpHeaders.SET_COOKIE,
                    "GILBOM_SESSION=; Path=/; Max-Age=0; "
                            + "HttpOnly; SameSite=Lax"
            );
            return null;
        }).when(logoutService).logout(any(), any(), any());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .with(csrf().asHeader()))
                .andExpect(status().isNoContent())
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL,
                        containsString("no-store")
                ))
                .andExpect(header().string(
                        HttpHeaders.SET_COOKIE,
                        containsString("Max-Age=0")
                ));
    }
}
