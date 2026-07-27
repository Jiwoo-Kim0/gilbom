package com.gilbom.robot.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.gilbom.auth.AuthenticatedUserIdResolver;
import com.gilbom.auth.model.AuthenticationState;
import com.gilbom.auth.session.SessionSecurityState;
import com.gilbom.auth.session.SessionSecurityStateRepository;
import com.gilbom.common.web.GlobalExceptionHandler;
import com.gilbom.common.web.RequestBodySizeLimitFilter;
import com.gilbom.common.web.RequestIdFilter;
import com.gilbom.robot.api.MqttCredentialIssuanceController;
import com.gilbom.robot.credential.MqttCredentialIssuanceService;
import com.gilbom.robot.data.application.result.IssuedMqttCredential;
import com.gilbom.security.config.SecurityConfig;
import com.gilbom.security.web.SecurityErrorResponseWriter;
import com.gilbom.security.web.SecurityHeadersFilter;

@WebMvcTest(controllers = MqttCredentialIssuanceController.class)
@Import({
        SecurityConfig.class,
        SecurityErrorResponseWriter.class,
        SecurityHeadersFilter.class,
        RequestIdFilter.class,
        RequestBodySizeLimitFilter.class,
        GlobalExceptionHandler.class,
        AuthenticatedUserIdResolver.class
})
class MqttCredentialIssuanceControllerTest {

    private static final UUID ACTOR_USER_ID =
            UUID.fromString("17000000-0000-4000-8000-000000000001");
    private static final UUID REQUEST_ID =
            UUID.fromString("fa29ea2d-3886-4576-8a04-cbb81713350d");
    private static final UUID IDEMPOTENCY_KEY =
            UUID.fromString("66b4d2de-0767-4685-8d89-3461d831253b");
    private static final UUID ROBOT_ID =
            UUID.fromString("f933fbf4-30c8-48cd-8382-6f3f90465d46");
    private static final UUID CREDENTIAL_ID =
            UUID.fromString("46ef4f98-2d7b-4c74-886f-f4da66381ba7");
    private static final SimpleGrantedAuthority ROBOT_REGISTER =
            new SimpleGrantedAuthority("ROBOT_REGISTER");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MqttCredentialIssuanceService issuanceService;

    @MockitoBean
    private SessionSecurityStateRepository sessionSecurityStateRepository;

    @BeforeEach
    void setUpAuthenticatedSessionState() {
        when(sessionSecurityStateRepository.findBySessionId(anyString()))
                .thenReturn(Optional.of(new SessionSecurityState(
                        ACTOR_USER_ID,
                        AuthenticationState.AUTHENTICATED,
                        Instant.parse("2099-01-01T00:00:00Z"),
                        null,
                        null,
                        0
                )));
    }

    @Test
    void returnsGeneratedMqttPasswordOnceWithNoStoreHeaders() throws Exception {
        Instant expiresAt = Instant.parse("2027-01-23T00:00:00Z");
        IssuedMqttCredential issued = new IssuedMqttCredential(
                CREDENTIAL_ID,
                "MQTT_PASSWORD",
                "ssl://125.137.58.131:8883",
                "RBT-001",
                "generated-one-time-password",
                "RBT-001",
                expiresAt);
        when(issuanceService.issue(
                eq(ROBOT_ID),
                eq(ACTOR_USER_ID),
                eq(IDEMPOTENCY_KEY),
                eq(REQUEST_ID)))
                .thenReturn(issued);

        mockMvc.perform(post(
                        "/api/v1/robots/{robotId}/credentials",
                        ROBOT_ID)
                        .with(user(ACTOR_USER_ID.toString())
                                .authorities(ROBOT_REGISTER))
                        .with(csrf().asHeader())
                        .header(RequestIdFilter.HEADER_NAME, REQUEST_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Location",
                        "/api/v1/robots/" + ROBOT_ID
                                + "/credentials/" + CREDENTIAL_ID))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath(
                        "$.credentialId",
                        is(CREDENTIAL_ID.toString())))
                .andExpect(jsonPath("$.credentialType", is("MQTT_PASSWORD")))
                .andExpect(jsonPath(
                        "$.mqtt.brokerUri",
                        is("ssl://125.137.58.131:8883")))
                .andExpect(jsonPath("$.mqtt.username", is("RBT-001")))
                .andExpect(jsonPath(
                        "$.mqtt.password",
                        is("generated-one-time-password")))
                .andExpect(jsonPath("$.mqtt.clientId", is("RBT-001")))
                .andExpect(jsonPath("$.secretHash").doesNotExist())
                .andExpect(jsonPath("$.secretFingerprint").doesNotExist());

        verify(issuanceService).issue(
                ROBOT_ID,
                ACTOR_USER_ID,
                IDEMPOTENCY_KEY,
                REQUEST_ID);
    }

    @Test
    void rejectsRequestWithoutRobotRegistrationPermission() throws Exception {
        mockMvc.perform(post(
                        "/api/v1/robots/{robotId}/credentials",
                        ROBOT_ID)
                        .with(user(ACTOR_USER_ID.toString()))
                        .with(csrf().asHeader())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("AUTH_FORBIDDEN")));

        verifyNoInteractions(issuanceService);
    }

    @Test
    void requiresCsrfProtection() throws Exception {
        mockMvc.perform(post(
                        "/api/v1/robots/{robotId}/credentials",
                        ROBOT_ID)
                        .with(user(ACTOR_USER_ID.toString())
                                .authorities(ROBOT_REGISTER))
                        .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("AUTH_FORBIDDEN")));

        verifyNoInteractions(issuanceService);
    }
}
