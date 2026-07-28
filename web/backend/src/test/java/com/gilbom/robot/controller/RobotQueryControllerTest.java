package com.gilbom.robot.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.gilbom.robot.api.PatrolCatalogController;
import com.gilbom.robot.api.RobotQueryController;
import com.gilbom.robot.application.RobotQueryService;
import com.gilbom.robot.data.api.response.PatrolCatalogDtos.PatrolAreaListResponse;
import com.gilbom.robot.data.api.response.PatrolCatalogDtos.PatrolAreaResponse;
import com.gilbom.robot.data.api.response.PatrolCatalogDtos.PatrolRouteListResponse;
import com.gilbom.robot.data.api.response.PatrolCatalogDtos.PatrolRouteResponse;
import com.gilbom.robot.data.api.response.RobotDetailResponse;
import com.gilbom.robot.data.api.response.RobotListItemResponse;
import com.gilbom.robot.data.api.response.RobotListResponse;
import com.gilbom.robot.mission.PatrolCatalogQueryService;
import com.gilbom.security.config.SecurityConfig;
import com.gilbom.security.web.SecurityErrorResponseWriter;
import com.gilbom.security.web.SecurityHeadersFilter;

@WebMvcTest(controllers = {
        RobotQueryController.class,
        PatrolCatalogController.class
})
@Import({
        SecurityConfig.class,
        SecurityErrorResponseWriter.class,
        SecurityHeadersFilter.class,
        RequestIdFilter.class,
        RequestBodySizeLimitFilter.class,
        GlobalExceptionHandler.class,
        AuthenticatedUserIdResolver.class
})
class RobotQueryControllerTest {

    private static final UUID USER_ID =
            UUID.fromString("17000000-0000-4000-8000-000000000011");
    private static final UUID ROBOT_ID =
            UUID.fromString("8750afcb-d682-4ef6-a90d-f63fcf5c2111");
    private static final UUID PATROL_AREA_ID =
            UUID.fromString("17000000-0000-4000-8000-000000000021");
    private static final UUID PATROL_ROUTE_ID =
            UUID.fromString("17000000-0000-4000-8000-000000000022");
    private static final SimpleGrantedAuthority ROBOT_STATUS_VIEW =
            new SimpleGrantedAuthority("ROBOT_STATUS_VIEW");
    private static final SimpleGrantedAuthority MISSION_VIEW =
            new SimpleGrantedAuthority("MISSION_VIEW");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RobotQueryService queryService;

    @MockitoBean
    private PatrolCatalogQueryService patrolCatalogQueryService;

    @MockitoBean
    private SessionSecurityStateRepository sessionSecurityStateRepository;

    @BeforeEach
    void setUpAuthenticatedSessionState() {
        when(sessionSecurityStateRepository.findBySessionId(
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.of(new SessionSecurityState(
                        USER_ID,
                        AuthenticationState.AUTHENTICATED,
                        Instant.parse("2099-01-01T00:00:00Z"),
                        null,
                        null,
                        0
                )));
    }

    @Test
    void returnsPagedRobotListWithPermission() throws Exception {
        when(queryService.findRobots(USER_ID, 0, 20, null))
                .thenReturn(new RobotListResponse(
                        List.of(new RobotListItemResponse(
                                ROBOT_ID,
                                "RBT-001",
                                "Patrol Robot",
                                "SN-001",
                                "ACTIVE",
                                "ONLINE",
                                Instant.parse("2026-07-28T01:00:00Z"),
                                new BigDecimal("87.50"),
                                false,
                                51.2,
                                0.8,
                                "PATROLLING",
                                "RUNNING",
                                "OK")),
                        0,
                        20,
                        1,
                        1));

        mockMvc.perform(get("/api/v1/robots")
                        .with(user(USER_ID.toString())
                                .authorities(ROBOT_STATUS_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].robotId", is(ROBOT_ID.toString())))
                .andExpect(jsonPath("$.content[0].mqttId", is("RBT-001")))
                .andExpect(jsonPath("$.content[0].connectivityStatus", is("ONLINE")))
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.totalElements", is(1)));
    }

    @Test
    @Tag("security-acceptance")
    void rejectsUnauthenticatedListRequest() throws Exception {
        mockMvc.perform(get("/api/v1/robots"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("AUTH_UNAUTHENTICATED")));

        verifyNoInteractions(queryService);
    }

    @Test
    void requiresRobotStatusViewPermission() throws Exception {
        mockMvc.perform(get("/api/v1/robots")
                        .with(user(USER_ID.toString())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("AUTH_FORBIDDEN")));

        verifyNoInteractions(queryService);
    }

    @Test
    @Tag("security-acceptance")
    void mapsOutOfScopeDetailToSameNotFoundResponse() throws Exception {
        when(queryService.findRobot(USER_ID, ROBOT_ID))
                .thenThrow(new BusinessException(ErrorCode.ROBOT_NOT_FOUND));

        mockMvc.perform(get("/api/v1/robots/{robotId}", ROBOT_ID)
                        .with(user(USER_ID.toString())
                                .authorities(ROBOT_STATUS_VIEW)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("ROBOT_NOT_FOUND")));
    }

    @Test
    void returnsPatrolAreaCatalogWithMissionView() throws Exception {
        when(patrolCatalogQueryService.findAreas())
                .thenReturn(new PatrolAreaListResponse(List.of(
                        new PatrolAreaResponse(
                                PATROL_AREA_ID,
                                "A-11",
                                "Fixture Area A",
                                new com.fasterxml.jackson.databind.ObjectMapper()
                                        .readTree("""
                                                {
                                                  "type": "Polygon",
                                                  "coordinates": []
                                                }
                                                """),
                                "ACTIVE",
                                Instant.parse("2026-07-01T00:00:00Z"),
                                Instant.parse("2026-07-01T00:00:00Z")))));

        mockMvc.perform(get("/api/v1/patrol-areas")
                        .with(user(USER_ID.toString())
                                .authorities(MISSION_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.areas", hasSize(1)))
                .andExpect(jsonPath("$.areas[0].patrolAreaId",
                        is(PATROL_AREA_ID.toString())))
                .andExpect(jsonPath("$.areas[0].areaCode", is("A-11")))
                .andExpect(jsonPath("$.areas[0].boundary.type",
                        is("Polygon")));
    }

    @Test
    void returnsAreaRoutesWithMissionView() throws Exception {
        when(patrolCatalogQueryService.findRoutes(PATROL_AREA_ID))
                .thenReturn(new PatrolRouteListResponse(List.of(
                        new PatrolRouteResponse(
                                PATROL_ROUTE_ID,
                                PATROL_AREA_ID,
                                "A-MAIN",
                                "Fixture Area A Main",
                                1,
                                new com.fasterxml.jackson.databind.ObjectMapper()
                                        .readTree("""
                                                {
                                                  "type": "LineString",
                                                  "coordinates": []
                                                }
                                                """),
                                "ACTIVE",
                                Instant.parse("2026-07-01T00:00:00Z"),
                                Instant.parse("2026-07-01T00:00:00Z")))));

        mockMvc.perform(get(
                        "/api/v1/patrol-areas/{patrolAreaId}/routes",
                        PATROL_AREA_ID)
                        .with(user(USER_ID.toString())
                                .authorities(MISSION_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routes", hasSize(1)))
                .andExpect(jsonPath("$.routes[0].patrolRouteId",
                        is(PATROL_ROUTE_ID.toString())))
                .andExpect(jsonPath("$.routes[0].routeCode",
                        is("A-MAIN")))
                .andExpect(jsonPath("$.routes[0].routeGeometry.type",
                        is("LineString")));
    }

    @Test
    void requiresMissionViewPermissionForPatrolCatalog()
            throws Exception {
        mockMvc.perform(get("/api/v1/patrol-areas")
                        .with(user(USER_ID.toString())
                                .authorities(ROBOT_STATUS_VIEW)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("AUTH_FORBIDDEN")));

        verifyNoInteractions(patrolCatalogQueryService);
    }

    @Test
    void mapsMissingPatrolAreaToNotFoundResponse() throws Exception {
        when(patrolCatalogQueryService.findArea(PATROL_AREA_ID))
                .thenThrow(new BusinessException(
                        ErrorCode.PATROL_AREA_NOT_FOUND));

        mockMvc.perform(get(
                        "/api/v1/patrol-areas/{patrolAreaId}",
                        PATROL_AREA_ID)
                        .with(user(USER_ID.toString())
                                .authorities(MISSION_VIEW)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code",
                        is("PATROL_AREA_NOT_FOUND")));
    }
}
