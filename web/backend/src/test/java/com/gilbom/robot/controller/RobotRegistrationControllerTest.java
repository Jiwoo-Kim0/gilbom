package com.gilbom.robot.controller;

import static org.hamcrest.Matchers.hasSize;
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
import org.junit.jupiter.api.Tag;
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
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.common.web.GlobalExceptionHandler;
import com.gilbom.common.web.RequestBodySizeLimitFilter;
import com.gilbom.common.web.RequestIdFilter;
import com.gilbom.robot.api.RobotRegistrationController;
import com.gilbom.robot.application.RobotRegistrationService;
import com.gilbom.robot.data.application.command.RegisterRobotCommand;
import com.gilbom.robot.data.application.result.RegisteredRobot;
import com.gilbom.security.config.SecurityConfig;
import com.gilbom.security.web.SecurityErrorResponseWriter;
import com.gilbom.security.web.SecurityHeadersFilter;

@WebMvcTest(controllers = RobotRegistrationController.class)
@Import({
        SecurityConfig.class,
        SecurityErrorResponseWriter.class,
        SecurityHeadersFilter.class,
        RequestIdFilter.class,
        RequestBodySizeLimitFilter.class,
        GlobalExceptionHandler.class,
        AuthenticatedUserIdResolver.class
})
class RobotRegistrationControllerTest {

    private static final UUID ACTOR_USER_ID =
            UUID.fromString("17000000-0000-4000-8000-000000000001");
    private static final UUID REQUEST_ID =
            UUID.fromString("e299ff0c-4a83-43c8-8212-ee1b537c5731");
    private static final UUID IDEMPOTENCY_KEY =
            UUID.fromString("bf86d40d-3f80-4af8-984a-b7a6d264aeab");
    private static final UUID ROBOT_ID =
            UUID.fromString("8750afcb-d682-4ef6-a90d-f63fcf5c214e");
    private static final SimpleGrantedAuthority ROBOT_REGISTER =
            new SimpleGrantedAuthority("ROBOT_REGISTER");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RobotRegistrationService registrationService;

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
    void registersRobotWithSessionActorAndReturnsCreatedDto() throws Exception {
        RegisterRobotCommand command = new RegisterRobotCommand(
                "점자블록 순찰 로봇 1호",
                "SN-JETSON-0001");
        RegisteredRobot registeredRobot = new RegisteredRobot(
                ROBOT_ID,
                "RBT-001",
                command.robotName(),
                command.serialNumber(),
                "PROVISIONING",
                "OFFLINE");
        when(registrationService.register(
                eq(command),
                eq(ACTOR_USER_ID),
                eq(IDEMPOTENCY_KEY),
                eq(REQUEST_ID)))
                .thenReturn(registeredRobot);

        mockMvc.perform(post("/api/v1/robots")
                        .with(user(ACTOR_USER_ID.toString())
                                .authorities(ROBOT_REGISTER))
                        .with(csrf().asHeader())
                        .header(RequestIdFilter.HEADER_NAME, REQUEST_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "robotName": "점자블록 순찰 로봇 1호",
                                  "serialNumber": "SN-JETSON-0001"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Location",
                        "/api/v1/robots/" + ROBOT_ID))
                .andExpect(header().string(
                        RequestIdFilter.HEADER_NAME,
                        REQUEST_ID.toString()))
                .andExpect(jsonPath("$.robotId", is(ROBOT_ID.toString())))
                .andExpect(jsonPath("$.mqttId", is("RBT-001")))
                .andExpect(jsonPath("$.robotName", is(command.robotName())))
                .andExpect(jsonPath("$.serialNumber", is(command.serialNumber())))
                .andExpect(jsonPath("$.operationalStatus", is("PROVISIONING")))
                .andExpect(jsonPath("$.connectivityStatus", is("OFFLINE")))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());

        verify(registrationService).register(
                command,
                ACTOR_USER_ID,
                IDEMPOTENCY_KEY,
                REQUEST_ID);
    }

    @Test
    void rejectsUnauthenticatedRegistration() throws Exception {
        mockMvc.perform(post("/api/v1/robots")
                        .with(csrf().asHeader())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("AUTH_UNAUTHENTICATED")));

        verifyNoInteractions(registrationService);
    }

    @Test
    @Tag("security-acceptance")
    void requiresRobotRegisterPermission() throws Exception {
        mockMvc.perform(post("/api/v1/robots")
                        .with(user(ACTOR_USER_ID.toString()))
                        .with(csrf().asHeader())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("AUTH_FORBIDDEN")));

        verifyNoInteractions(registrationService);
    }

    @Test
    @Tag("security-acceptance")
    void requiresCsrfTokenForRegistration() throws Exception {
        mockMvc.perform(post("/api/v1/robots")
                        .with(user(ACTOR_USER_ID.toString())
                                .authorities(ROBOT_REGISTER))
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("AUTH_FORBIDDEN")));

        verifyNoInteractions(registrationService);
    }

    @Test
    void requiresIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/robots")
                        .with(user(ACTOR_USER_ID.toString())
                                .authorities(ROBOT_REGISTER))
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("COMMON_INVALID_REQUEST")))
                .andExpect(jsonPath("$.details", hasSize(1)))
                .andExpect(jsonPath(
                        "$.details[0].field",
                        is("Idempotency-Key")));

        verifyNoInteractions(registrationService);
    }

    @Test
    void validatesRobotNameAndSerialNumber() throws Exception {
        mockMvc.perform(post("/api/v1/robots")
                        .with(user(ACTOR_USER_ID.toString())
                                .authorities(ROBOT_REGISTER))
                        .with(csrf().asHeader())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "robotName": " ",
                                  "serialNumber": "SN INVALID"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("COMMON_VALIDATION_FAILED")))
                .andExpect(jsonPath("$.details", hasSize(3)));

        verifyNoInteractions(registrationService);
    }

    @Test
    void mapsDuplicateSerialNumberToConflict() throws Exception {
        when(registrationService.register(
                eq(new RegisterRobotCommand(
                        "점자블록 순찰 로봇 1호",
                        "SN-JETSON-0001")),
                eq(ACTOR_USER_ID),
                eq(IDEMPOTENCY_KEY),
                eq(REQUEST_ID)))
                .thenThrow(new BusinessException(
                        ErrorCode.ROBOT_SERIAL_NUMBER_CONFLICT));

        mockMvc.perform(post("/api/v1/robots")
                        .with(user(ACTOR_USER_ID.toString())
                                .authorities(ROBOT_REGISTER))
                        .with(csrf().asHeader())
                        .header(RequestIdFilter.HEADER_NAME, REQUEST_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath(
                        "$.code",
                        is("ROBOT_SERIAL_NUMBER_CONFLICT")))
                .andExpect(jsonPath("$.status", is(409)));
    }

    private String validRequest() {
        return """
                {
                  "robotName": "점자블록 순찰 로봇 1호",
                  "serialNumber": "SN-JETSON-0001"
                }
                """;
    }
}
