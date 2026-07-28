package com.gilbom.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.gilbom.auth.session.SessionSecurityStateRepository;
import com.gilbom.security.config.SecurityConfig;
import com.gilbom.security.web.SecurityErrorResponseWriter;

@WebMvcTest(
        controllers = SecurityTestController.class,
        properties = {
                "gilbom.security.cors.enabled=true",
                "gilbom.security.cors.allowed-origins="
                        + "https://admin.example.com",
                "gilbom.security.cors.allow-credentials=true"
        }
)
@Import({
        SecurityConfig.class,
        SecurityErrorResponseWriter.class
})
class CorsEnabledSecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SessionSecurityStateRepository sessionStateRepository;

    @Test
    void listedOriginReceivesCredentialedPreflightHeaders() throws Exception {
        mockMvc.perform(options("/api/v1/auth/login")
                        .header("Origin", "https://admin.example.com")
                        .header("Access-Control-Request-Method", "POST")
                        .header(
                                "Access-Control-Request-Headers",
                                "Content-Type, X-CSRF-TOKEN"
                        ))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Access-Control-Allow-Origin",
                        "https://admin.example.com"
                ))
                .andExpect(header().string(
                        "Access-Control-Allow-Credentials",
                        "true"
                ));
    }

    @Test
    void unlistedOriginIsRejectedWithoutCorsHeaders() throws Exception {
        mockMvc.perform(options("/api/v1/auth/login")
                        .header("Origin", "https://attacker.example.com")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(
                        "Access-Control-Allow-Origin"
                ))
                .andExpect(header().doesNotExist(
                        "Access-Control-Allow-Credentials"
                ));
    }
}
