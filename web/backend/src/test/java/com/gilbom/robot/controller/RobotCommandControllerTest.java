package com.gilbom.robot.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

import com.gilbom.audit.AuditRequestContext;
import com.gilbom.auth.AuthenticatedUserIdResolver;
import com.gilbom.auth.model.AuthenticationState;
import com.gilbom.auth.session.SessionSecurityState;
import com.gilbom.auth.session.SessionSecurityStateRepository;
import com.gilbom.common.web.GlobalExceptionHandler;
import com.gilbom.common.web.RequestBodySizeLimitFilter;
import com.gilbom.common.web.RequestIdFilter;
import com.gilbom.robot.api.RobotCommandController;
import com.gilbom.robot.command.RobotCommandQueryService;
import com.gilbom.robot.command.RobotCommandService;
import com.gilbom.robot.data.api.request.RobotCommandCreateRequest;
import com.gilbom.robot.data.api.response.RobotCommandResponse;
import com.gilbom.robot.data.mqtt.v2.outbound.CommandRequestPayload;
import com.gilbom.security.config.SecurityConfig;
import com.gilbom.security.web.SecurityErrorResponseWriter;
import com.gilbom.security.web.SecurityHeadersFilter;

@WebMvcTest(controllers = RobotCommandController.class)
@Import({
        SecurityConfig.class,
        SecurityErrorResponseWriter.class,
        SecurityHeadersFilter.class,
        RequestIdFilter.class,
        RequestBodySizeLimitFilter.class,
        GlobalExceptionHandler.class,
        AuthenticatedUserIdResolver.class
})
class RobotCommandControllerTest {

    private static final UUID USER_ID =
            UUID.fromString("17000000-0000-4000-8000-000000000021");
    private static final UUID ROBOT_ID =
            UUID.fromString("8750afcb-d682-4ef6-a90d-f63fcf5c2121");
    private static final UUID COMMAND_ID =
            UUID.fromString("8750afcb-d682-4ef6-a90d-f63fcf5c2122");
    private static final UUID IDEMPOTENCY_KEY =
            UUID.fromString("8750afcb-d682-4ef6-a90d-f63fcf5c2123");
    private static final SimpleGrantedAuthority COMMAND_EXECUTE =
            new SimpleGrantedAuthority("ROBOT_COMMAND_EXECUTE");
    private static final SimpleGrantedAuthority COMMAND_VIEW =
            new SimpleGrantedAuthority("ROBOT_COMMAND_VIEW");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RobotCommandService commandService;

    @MockitoBean
    private RobotCommandQueryService queryService;

    @MockitoBean
    private SessionSecurityStateRepository sessionSecurityStateRepository;

    @BeforeEach
    void setUpAuthenticatedSessionState() {
        when(sessionSecurityStateRepository.findBySessionId(any(String.class)))
                .thenReturn(Optional.of(new SessionSecurityState(
                        USER_ID,
                        AuthenticationState.AUTHENTICATED,
                        Instant.parse("2099-01-01T00:00:00Z"),
                        null,
                        null,
                        0)));
    }

    @Test
    void createsCommandWithPermissionCsrfAndIdempotencyKey()
            throws Exception {
        when(commandService.createAndPublish(
                eq(ROBOT_ID),
                any(RobotCommandCreateRequest.class),
                eq(USER_ID),
                eq(IDEMPOTENCY_KEY),
                any(AuditRequestContext.class)))
                .thenReturn(response());

        mockMvc.perform(post(
                        "/api/v1/robots/{robotId}/commands",
                        ROBOT_ID)
                        .with(user(USER_ID.toString())
                                .authorities(COMMAND_EXECUTE))
                        .with(csrf().asHeader())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Location",
                        "/api/v1/commands/" + COMMAND_ID))
                .andExpect(jsonPath(
                        "$.commandStatus",
                        is("SENT")));
    }

    @Test
    @Tag("security-acceptance")
    void rejectsCommandWithoutCsrf() throws Exception {
        mockMvc.perform(post(
                        "/api/v1/robots/{robotId}/commands",
                        ROBOT_ID)
                        .with(user(USER_ID.toString())
                                .authorities(COMMAND_EXECUTE))
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("AUTH_FORBIDDEN")));

        verifyNoInteractions(commandService);
    }

    @Test
    void rejectsCommandWithoutExecutePermission() throws Exception {
        mockMvc.perform(post(
                        "/api/v1/robots/{robotId}/commands",
                        ROBOT_ID)
                        .with(user(USER_ID.toString())
                                .authorities(COMMAND_VIEW))
                        .with(csrf().asHeader())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("AUTH_FORBIDDEN")));

        verifyNoInteractions(commandService);
    }

    @Test
    void validatesCommandSpecificParametersBeforePublishing()
            throws Exception {
        mockMvc.perform(post(
                        "/api/v1/robots/{robotId}/commands",
                        ROBOT_ID)
                        .with(user(USER_ID.toString())
                                .authorities(COMMAND_EXECUTE))
                        .with(csrf().asHeader())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "commandType": "RESUME_MISSION",
                                  "parameters": {
                                    "reason": "must be empty"
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath(
                        "$.code",
                        is("COMMON_VALIDATION_FAILED")));

        verifyNoInteractions(commandService);
    }

    @Test
    void requiresViewPermissionForCommandDetail() throws Exception {
        when(queryService.findCommand(USER_ID, COMMAND_ID))
                .thenReturn(response());

        mockMvc.perform(get(
                        "/api/v1/commands/{commandId}",
                        COMMAND_ID)
                        .with(user(USER_ID.toString())
                                .authorities(COMMAND_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.commandId",
                        is(COMMAND_ID.toString())));
    }

    private String validRequest() {
        return """
                {
                  "commandType": "EMERGENCY_STOP",
                  "parameters": {
                    "reason": "operator requested stop"
                  }
                }
                """;
    }

    private RobotCommandResponse response() {
        Instant issuedAt = Instant.parse("2026-07-28T07:00:00Z");
        return new RobotCommandResponse(
                COMMAND_ID,
                ROBOT_ID,
                "RBT-021",
                CommandRequestPayload.CommandType.EMERGENCY_STOP,
                "SENT",
                new RobotCommandCreateRequest.Parameters(
                        null,
                        null,
                        "operator requested stop"),
                "ADMIN",
                USER_ID,
                "ADM-021",
                issuedAt,
                issuedAt.plusSeconds(10),
                issuedAt.plusMillis(100),
                null,
                null,
                1,
                null,
                issuedAt,
                issuedAt.plusMillis(100));
    }
}
