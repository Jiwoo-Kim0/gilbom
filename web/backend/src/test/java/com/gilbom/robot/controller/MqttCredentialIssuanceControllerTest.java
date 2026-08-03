package com.gilbom.robot.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
import com.gilbom.robot.credential.MqttCredentialRegistryService;
import com.gilbom.robot.data.api.response.MqttCredentialResponse;
import com.gilbom.robot.data.api.response.MqttCredentialRegistryStatusResponse;
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
    private MqttCredentialRegistryService registryService;

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
                ROBOT_ID,
                "RBT-001",
                CREDENTIAL_ID,
                "MQTT_PASSWORD",
                "ssl://125.137.58.131:8883",
                "RBT-001",
                "generated-one-time-password",
                "RBT-001",
                expiresAt,
                "https://example.test/api/v1/images",
                "gik_generated-one-time-image-key");
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
                        "$.robotId",
                        is(ROBOT_ID.toString())))
                .andExpect(jsonPath("$.mqttId", is("RBT-001")))
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
                .andExpect(jsonPath(
                        "$.imageUpload.url",
                        is("https://example.test/api/v1/images")))
                .andExpect(jsonPath(
                        "$.imageUpload.apiKey",
                        is("gik_generated-one-time-image-key")))
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

    @Test
    void listsCredentialMetadataWithoutCsrfToken() throws Exception {
        Instant issuedAt = Instant.parse("2026-07-29T07:00:00Z");
        Instant expiresAt = Instant.parse("2027-01-25T07:00:00Z");
        MqttCredentialResponse credential =
                new MqttCredentialResponse(
                        CREDENTIAL_ID,
                        "MQTT_PASSWORD",
                        1,
                        "ACTIVE",
                        ACTOR_USER_ID,
                        issuedAt,
                        expiresAt,
                        issuedAt,
                        null,
                        null,
                        null);
        when(issuanceService.list(ROBOT_ID, ACTOR_USER_ID))
                .thenReturn(List.of(credential));

        mockMvc.perform(get(
                        "/api/v1/robots/{robotId}/credentials",
                        ROBOT_ID)
                        .with(user(ACTOR_USER_ID.toString())
                                .authorities(ROBOT_REGISTER)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath(
                        "$.credentials[0].credentialId",
                        is(CREDENTIAL_ID.toString())))
                .andExpect(jsonPath(
                        "$.credentials[0].credentialStatus",
                        is("ACTIVE")))
                .andExpect(jsonPath("$.credentials[0].password")
                        .doesNotExist())
                .andExpect(jsonPath("$.credentials[0].secretHash")
                        .doesNotExist())
                .andExpect(jsonPath(
                        "$.credentials[0].secretFingerprint")
                        .doesNotExist());

        verify(issuanceService).list(ROBOT_ID, ACTOR_USER_ID);
    }

    @Test
    void revokesCredentialWithReasonAndCsrfProtection()
            throws Exception {
        Instant issuedAt = Instant.parse("2026-07-29T07:00:00Z");
        Instant expiresAt = Instant.parse("2027-01-25T07:00:00Z");
        Instant revokedAt = Instant.parse("2026-07-29T08:00:00Z");
        MqttCredentialResponse revoked =
                new MqttCredentialResponse(
                        CREDENTIAL_ID,
                        "MQTT_PASSWORD",
                        1,
                        "REVOKED",
                        ACTOR_USER_ID,
                        issuedAt,
                        expiresAt,
                        issuedAt,
                        ACTOR_USER_ID,
                        revokedAt,
                        "DEVICE_RETIRED");
        when(issuanceService.revoke(
                ROBOT_ID,
                CREDENTIAL_ID,
                ACTOR_USER_ID,
                "DEVICE_RETIRED",
                REQUEST_ID))
                .thenReturn(revoked);

        mockMvc.perform(post(
                        "/api/v1/robots/{robotId}/credentials/"
                                + "{credentialId}/revoke",
                        ROBOT_ID,
                        CREDENTIAL_ID)
                        .with(user(ACTOR_USER_ID.toString())
                                .authorities(ROBOT_REGISTER))
                        .with(csrf().asHeader())
                        .header(RequestIdFilter.HEADER_NAME, REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": " DEVICE_RETIRED "
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath(
                        "$.credentialStatus",
                        is("REVOKED")))
                .andExpect(jsonPath(
                        "$.revocationReason",
                        is("DEVICE_RETIRED")));

        verify(issuanceService).revoke(
                ROBOT_ID,
                CREDENTIAL_ID,
                ACTOR_USER_ID,
                "DEVICE_RETIRED",
                REQUEST_ID);
    }

    @Test
    void requiresCsrfProtectionForCredentialRevocation()
            throws Exception {
        mockMvc.perform(post(
                        "/api/v1/robots/{robotId}/credentials/"
                                + "{credentialId}/revoke",
                        ROBOT_ID,
                        CREDENTIAL_ID)
                        .with(user(ACTOR_USER_ID.toString())
                                .authorities(ROBOT_REGISTER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "DEVICE_RETIRED"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("AUTH_FORBIDDEN")));

        verifyNoInteractions(issuanceService);
    }

    @Test
    void returnsSafeCredentialRegistryStatusWithoutCsrfToken()
            throws Exception {
        Instant checkedAt = Instant.parse("2026-07-29T08:30:00Z");
        when(registryService.status(ROBOT_ID, ACTOR_USER_ID))
                .thenReturn(new MqttCredentialRegistryStatusResponse(
                        ROBOT_ID,
                        "RBT-001",
                        "MISMATCH",
                        true,
                        CREDENTIAL_ID,
                        2,
                        checkedAt));

        mockMvc.perform(get(
                        "/api/v1/robots/{robotId}/credentials/"
                                + "registry-status",
                        ROBOT_ID)
                        .with(user(ACTOR_USER_ID.toString())
                                .authorities(ROBOT_REGISTER)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.registryStatus", is("MISMATCH")))
                .andExpect(jsonPath("$.driftDetected", is(true)))
                .andExpect(jsonPath("$.secretHash").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        verify(registryService).status(ROBOT_ID, ACTOR_USER_ID);
    }

    @Test
    void reconcilesCredentialRegistryWithCsrfAndReason()
            throws Exception {
        when(registryService.reconcile(
                ROBOT_ID,
                ACTOR_USER_ID,
                "MANUAL_RECOVERY",
                REQUEST_ID))
                .thenReturn(new MqttCredentialRegistryStatusResponse(
                        ROBOT_ID,
                        "RBT-001",
                        "IN_SYNC",
                        false,
                        CREDENTIAL_ID,
                        2,
                        Instant.parse("2026-07-29T08:31:00Z")));

        mockMvc.perform(post(
                        "/api/v1/robots/{robotId}/credentials/"
                                + "registry-reconcile",
                        ROBOT_ID)
                        .with(user(ACTOR_USER_ID.toString())
                                .authorities(ROBOT_REGISTER))
                        .with(csrf().asHeader())
                        .header(RequestIdFilter.HEADER_NAME, REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": " MANUAL_RECOVERY "
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.registryStatus", is("IN_SYNC")))
                .andExpect(jsonPath("$.driftDetected", is(false)));

        verify(registryService).reconcile(
                ROBOT_ID,
                ACTOR_USER_ID,
                "MANUAL_RECOVERY",
                REQUEST_ID);
    }

    @Test
    void requiresCsrfForCredentialRegistryReconciliation()
            throws Exception {
        mockMvc.perform(post(
                        "/api/v1/robots/{robotId}/credentials/"
                                + "registry-reconcile",
                        ROBOT_ID)
                        .with(user(ACTOR_USER_ID.toString())
                                .authorities(ROBOT_REGISTER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "MANUAL_RECOVERY"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("AUTH_FORBIDDEN")));

        verifyNoInteractions(registryService);
    }
}
