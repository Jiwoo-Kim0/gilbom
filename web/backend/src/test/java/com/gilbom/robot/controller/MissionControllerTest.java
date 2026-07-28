package com.gilbom.robot.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
import com.gilbom.robot.api.MissionController;
import com.gilbom.robot.data.api.response.MissionDtos.MissionCreateRequest;
import com.gilbom.robot.data.api.response.MissionDtos.MissionListResponse;
import com.gilbom.robot.data.api.response.MissionDtos.MissionResponse;
import com.gilbom.robot.data.api.response.MissionDtos.MissionStatus;
import com.gilbom.robot.data.api.response.MissionDtos.MissionUpdateRequest;
import com.gilbom.robot.mission.MissionManagementService;
import com.gilbom.robot.mission.MissionQueryService;
import com.gilbom.security.config.SecurityConfig;
import com.gilbom.security.web.SecurityErrorResponseWriter;
import com.gilbom.security.web.SecurityHeadersFilter;

@WebMvcTest(controllers = MissionController.class)
@Import({
        SecurityConfig.class,
        SecurityErrorResponseWriter.class,
        SecurityHeadersFilter.class,
        RequestIdFilter.class,
        RequestBodySizeLimitFilter.class,
        GlobalExceptionHandler.class,
        AuthenticatedUserIdResolver.class
})
class MissionControllerTest {

    private static final UUID USER_ID =
            UUID.fromString("25000000-0000-4000-8000-000000000001");
    private static final UUID MISSION_ID =
            UUID.fromString("25000000-0000-4000-8000-000000000002");
    private static final UUID ROBOT_ID =
            UUID.fromString("25000000-0000-4000-8000-000000000003");
    private static final UUID AREA_ID =
            UUID.fromString("25000000-0000-4000-8000-000000000004");
    private static final UUID ROUTE_ID =
            UUID.fromString("25000000-0000-4000-8000-000000000005");
    private static final SimpleGrantedAuthority MISSION_VIEW =
            new SimpleGrantedAuthority("MISSION_VIEW");
    private static final SimpleGrantedAuthority MISSION_MANAGE =
            new SimpleGrantedAuthority("MISSION_MANAGE");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MissionManagementService managementService;

    @MockitoBean
    private MissionQueryService queryService;

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
    void createsMissionWithManagePermissionAndCsrf() throws Exception {
        when(managementService.create(
                any(MissionCreateRequest.class),
                eq(USER_ID),
                any(AuditRequestContext.class)))
                .thenReturn(response(MissionStatus.CREATED));

        mockMvc.perform(post("/api/v1/missions")
                        .with(user(USER_ID.toString())
                                .authorities(MISSION_MANAGE))
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "robotId": "%s",
                                  "patrolAreaId": "%s",
                                  "patrolRouteId": "%s",
                                  "scheduledStartAt":
                                    "2099-01-01T01:00:00Z"
                                }
                                """.formatted(
                                ROBOT_ID,
                                AREA_ID,
                                ROUTE_ID)))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Location",
                        "/api/v1/missions/" + MISSION_ID))
                .andExpect(jsonPath("$.missionId", is(
                        MISSION_ID.toString())))
                .andExpect(jsonPath("$.patrolRouteId", is(
                        ROUTE_ID.toString())));
    }

    @Test
    @Tag("security-acceptance")
    void rejectsMissionMutationWithoutCsrf() throws Exception {
        mockMvc.perform(patch(
                        "/api/v1/missions/{missionId}",
                        MISSION_ID)
                        .with(user(USER_ID.toString())
                                .authorities(MISSION_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"READY"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("AUTH_FORBIDDEN")));

        verifyNoInteractions(managementService);
    }

    @Test
    void listsMissionsWithViewPermission() throws Exception {
        when(queryService.findMissions(
                USER_ID,
                0,
                20,
                null,
                null,
                null,
                null,
                null,
                null))
                .thenReturn(new MissionListResponse(
                        List.of(response(MissionStatus.READY)),
                        0,
                        20,
                        1,
                        1));

        mockMvc.perform(get("/api/v1/missions")
                        .with(user(USER_ID.toString())
                                .authorities(MISSION_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status", is("READY")))
                .andExpect(jsonPath("$.totalElements", is(1)));
    }

    @Test
    void rejectsMissionListWithoutViewPermission() throws Exception {
        mockMvc.perform(get("/api/v1/missions")
                        .with(user(USER_ID.toString())
                                .authorities(MISSION_MANAGE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("AUTH_FORBIDDEN")));

        verifyNoInteractions(queryService);
    }

    private MissionResponse response(MissionStatus status) {
        Instant createdAt = Instant.parse("2026-07-28T01:00:00Z");
        return new MissionResponse(
                MISSION_ID,
                "MIS-20260728-001",
                ROBOT_ID,
                "RBT-001",
                "Robot 1",
                AREA_ID,
                "AREA-001",
                "Area 1",
                ROUTE_ID,
                "ROUTE-001",
                "Route 1",
                1,
                status,
                Instant.parse("2099-01-01T01:00:00Z"),
                null,
                null,
                new BigDecimal("0.00"),
                null,
                null,
                null,
                null,
                null,
                null,
                USER_ID,
                USER_ID,
                createdAt,
                createdAt);
    }
}
