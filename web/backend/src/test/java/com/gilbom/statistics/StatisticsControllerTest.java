package com.gilbom.statistics;

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
import com.gilbom.security.config.SecurityConfig;
import com.gilbom.security.web.SecurityErrorResponseWriter;
import com.gilbom.security.web.SecurityHeadersFilter;
import com.gilbom.statistics.StatisticsDtos.DefectType;
import com.gilbom.statistics.StatisticsDtos.DefectWorkflowStatus;
import com.gilbom.statistics.StatisticsDtos.StatisticsAggregateMetrics;
import com.gilbom.statistics.StatisticsDtos.StatisticsByAreaItem;
import com.gilbom.statistics.StatisticsDtos.StatisticsByAreaResponse;
import com.gilbom.statistics.StatisticsDtos.StatisticsByDefectTypeItem;
import com.gilbom.statistics.StatisticsDtos.StatisticsByDefectTypeResponse;
import com.gilbom.statistics.StatisticsDtos.StatisticsByStatusItem;
import com.gilbom.statistics.StatisticsDtos.StatisticsByStatusResponse;

@WebMvcTest(controllers = StatisticsController.class)
@Import({
        SecurityConfig.class,
        SecurityErrorResponseWriter.class,
        SecurityHeadersFilter.class,
        RequestIdFilter.class,
        RequestBodySizeLimitFilter.class,
        GlobalExceptionHandler.class,
        AuthenticatedUserIdResolver.class
})
class StatisticsControllerTest {

    private static final UUID USER_ID =
            UUID.fromString("17000000-0000-4000-8000-000000000011");
    private static final Instant FROM =
            Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant TO =
            Instant.parse("2026-07-05T00:00:00Z");
    private static final SimpleGrantedAuthority DEFECT_STATUS_VIEW =
            new SimpleGrantedAuthority("DEFECT_STATUS_VIEW");
    private static final SimpleGrantedAuthority MISSION_VIEW =
            new SimpleGrantedAuthority("MISSION_VIEW");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StatisticsQueryService queryService;

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
    void returnsStatusStatisticsWithRequiredPermission() throws Exception {
        when(queryService.findByStatus(USER_ID, FROM, TO))
                .thenReturn(response());

        mockMvc.perform(get("/api/v1/statistics/by-status")
                        .param("from", "2026-07-01T09:00:00+09:00")
                        .param("to", "2026-07-05T09:00:00+09:00")
                        .with(user(USER_ID.toString())
                                .authorities(DEFECT_STATUS_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from",
                        is("2026-07-01T00:00:00Z")))
                .andExpect(jsonPath("$.to",
                        is("2026-07-05T00:00:00Z")))
                .andExpect(jsonPath("$.totalDefectCount", is(4)))
                .andExpect(jsonPath("$.items", hasSize(6)))
                .andExpect(jsonPath("$.items[0].workflowStatus", is("NEW")))
                .andExpect(jsonPath("$.items[0].defectCount", is(2)))
                .andExpect(jsonPath("$.items[5].workflowStatus",
                        is("REJECTED")))
                .andExpect(jsonPath("$.items[5].defectCount", is(0)));
    }

    @Test
    void rejectsUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/api/v1/statistics/by-status")
                        .param("from", FROM.toString())
                        .param("to", TO.toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code",
                        is("AUTH_UNAUTHENTICATED")));

        verifyNoInteractions(queryService);
    }

    @Test
    void requiresDefectStatusViewPermission() throws Exception {
        mockMvc.perform(get("/api/v1/statistics/by-status")
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .with(user(USER_ID.toString())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("AUTH_FORBIDDEN")));

        verifyNoInteractions(queryService);
    }

    @Test
    void rejectsMissingRequiredBoundary() throws Exception {
        mockMvc.perform(get("/api/v1/statistics/by-status")
                        .param("from", FROM.toString())
                        .with(user(USER_ID.toString())
                                .authorities(DEFECT_STATUS_VIEW)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code",
                        is("COMMON_INVALID_REQUEST")));

        verifyNoInteractions(queryService);
    }

    @Test
    void rejectsMalformedTimestamp() throws Exception {
        mockMvc.perform(get("/api/v1/statistics/by-status")
                        .param("from", "2026-07-01")
                        .param("to", TO.toString())
                        .with(user(USER_ID.toString())
                                .authorities(DEFECT_STATUS_VIEW)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code",
                        is("COMMON_INVALID_REQUEST")));

        verifyNoInteractions(queryService);
    }

    @Test
    void mapsSemanticPeriodViolationTo422() throws Exception {
        when(queryService.findByStatus(USER_ID, TO, FROM))
                .thenThrow(new BusinessException(
                        ErrorCode.STATISTICS_PERIOD_INVALID));

        mockMvc.perform(get("/api/v1/statistics/by-status")
                        .param("from", TO.toString())
                        .param("to", FROM.toString())
                        .with(user(USER_ID.toString())
                                .authorities(DEFECT_STATUS_VIEW)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code",
                        is("STATISTICS_PERIOD_INVALID")));
    }

    @Test
    void returnsDefectTypeStatisticsWithRequiredPermission()
            throws Exception {
        when(queryService.findByDefectType(USER_ID, FROM, TO))
                .thenReturn(defectTypeResponse());

        mockMvc.perform(get("/api/v1/statistics/by-defect-type")
                        .param("from", "2026-07-01T09:00:00+09:00")
                        .param("to", "2026-07-05T09:00:00+09:00")
                        .with(user(USER_ID.toString())
                                .authorities(DEFECT_STATUS_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from",
                        is("2026-07-01T00:00:00Z")))
                .andExpect(jsonPath("$.to",
                        is("2026-07-05T00:00:00Z")))
                .andExpect(jsonPath("$.totalDefectCount", is(4)))
                .andExpect(jsonPath("$.items", hasSize(7)))
                .andExpect(jsonPath("$.items[0].defectType",
                        is("DAMAGED")))
                .andExpect(jsonPath("$.items[0].defectCount", is(1)))
                .andExpect(jsonPath("$.items[6].defectType",
                        is("UNKNOWN_ANOMALY")))
                .andExpect(jsonPath("$.items[6].defectCount", is(0)));
    }

    @Test
    void defectTypeStatisticsRejectUnauthenticatedRequest()
            throws Exception {
        mockMvc.perform(get("/api/v1/statistics/by-defect-type")
                        .param("from", FROM.toString())
                        .param("to", TO.toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code",
                        is("AUTH_UNAUTHENTICATED")));

        verifyNoInteractions(queryService);
    }

    @Test
    void defectTypeStatisticsRequireDefectStatusViewPermission()
            throws Exception {
        mockMvc.perform(get("/api/v1/statistics/by-defect-type")
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .with(user(USER_ID.toString())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("AUTH_FORBIDDEN")));

        verifyNoInteractions(queryService);
    }

    @Test
    void rejectsInjectionShapedUncontractedDefectTypeParameter()
            throws Exception {
        mockMvc.perform(get("/api/v1/statistics/by-defect-type")
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .param(
                                "defectType",
                                "DAMAGED; DROP TABLE defect.defect")
                        .with(user(USER_ID.toString())
                                .authorities(DEFECT_STATUS_VIEW)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code",
                        is("COMMON_INVALID_REQUEST")));

        verifyNoInteractions(queryService);
    }

    @Test
    void defectTypeStatisticsMapInvalidPeriodTo422()
            throws Exception {
        when(queryService.findByDefectType(USER_ID, TO, FROM))
                .thenThrow(new BusinessException(
                        ErrorCode.STATISTICS_PERIOD_INVALID));

        mockMvc.perform(get("/api/v1/statistics/by-defect-type")
                        .param("from", TO.toString())
                        .param("to", FROM.toString())
                        .with(user(USER_ID.toString())
                                .authorities(DEFECT_STATUS_VIEW)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code",
                        is("STATISTICS_PERIOD_INVALID")));
    }

    @Test
    void returnsAreaStatisticsWithBothRequiredPermissions()
            throws Exception {
        when(queryService.findByArea(
                USER_ID,
                FROM,
                TO,
                null,
                0,
                20))
                .thenReturn(areaResponse());

        mockMvc.perform(get("/api/v1/statistics/by-area")
                        .param("from", "2026-07-01T09:00:00+09:00")
                        .param("to", "2026-07-05T09:00:00+09:00")
                        .with(user(USER_ID.toString()).authorities(
                                MISSION_VIEW,
                                DEFECT_STATUS_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from",
                        is("2026-07-01T00:00:00Z")))
                .andExpect(jsonPath("$.to",
                        is("2026-07-05T00:00:00Z")))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].areaCode", is("A-11")))
                .andExpect(jsonPath(
                        "$.items[0].metrics.missionCount",
                        is(2)))
                .andExpect(jsonPath(
                        "$.items[0].metrics.incidentEventCount",
                        is(3)))
                .andExpect(jsonPath(
                        "$.items[0].metrics.defectCount",
                        is(2)))
                .andExpect(jsonPath(
                        "$.items[0].metrics.observationCount",
                        is(3)))
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(20)))
                .andExpect(jsonPath("$.totalElements", is(2)))
                .andExpect(jsonPath("$.totalPages", is(1)));
    }

    @Test
    void areaStatisticsRequireMissionViewPermission()
            throws Exception {
        mockMvc.perform(get("/api/v1/statistics/by-area")
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .with(user(USER_ID.toString())
                                .authorities(DEFECT_STATUS_VIEW)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("AUTH_FORBIDDEN")));

        verifyNoInteractions(queryService);
    }

    @Test
    void areaStatisticsRequireDefectStatusViewPermission()
            throws Exception {
        mockMvc.perform(get("/api/v1/statistics/by-area")
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .with(user(USER_ID.toString())
                                .authorities(MISSION_VIEW)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("AUTH_FORBIDDEN")));

        verifyNoInteractions(queryService);
    }

    @Test
    void areaStatisticsRejectMalformedAreaUuid()
            throws Exception {
        mockMvc.perform(get("/api/v1/statistics/by-area")
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .param("patrolAreaId", "not-a-uuid")
                        .with(user(USER_ID.toString()).authorities(
                                MISSION_VIEW,
                                DEFECT_STATUS_VIEW)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code",
                        is("COMMON_INVALID_REQUEST")));

        verifyNoInteractions(queryService);
    }

    @Test
    void areaStatisticsMapMissingAreaTo404()
            throws Exception {
        UUID missingAreaId = UUID.fromString(
                "30000000-0000-4000-8000-000000000099");
        when(queryService.findByArea(
                USER_ID,
                FROM,
                TO,
                missingAreaId,
                0,
                20))
                .thenThrow(new BusinessException(
                        ErrorCode.PATROL_AREA_NOT_FOUND));

        mockMvc.perform(get("/api/v1/statistics/by-area")
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .param(
                                "patrolAreaId",
                                missingAreaId.toString())
                        .with(user(USER_ID.toString()).authorities(
                                MISSION_VIEW,
                                DEFECT_STATUS_VIEW)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code",
                        is("PATROL_AREA_NOT_FOUND")));
    }

    private static StatisticsByStatusResponse response() {
        return new StatisticsByStatusResponse(
                FROM,
                TO,
                4,
                List.of(
                        new StatisticsByStatusItem(
                                DefectWorkflowStatus.NEW, 2),
                        new StatisticsByStatusItem(
                                DefectWorkflowStatus.ACCEPTED, 1),
                        new StatisticsByStatusItem(
                                DefectWorkflowStatus.IN_PROGRESS, 1),
                        new StatisticsByStatusItem(
                                DefectWorkflowStatus.RESOLVED, 0),
                        new StatisticsByStatusItem(
                                DefectWorkflowStatus.FALSE_POSITIVE, 0),
                        new StatisticsByStatusItem(
                                DefectWorkflowStatus.REJECTED, 0)));
    }

    private static StatisticsByDefectTypeResponse defectTypeResponse() {
        return new StatisticsByDefectTypeResponse(
                FROM,
                TO,
                4,
                List.of(
                        new StatisticsByDefectTypeItem(
                                DefectType.DAMAGED, 1),
                        new StatisticsByDefectTypeItem(
                                DefectType.CONTAMINATED, 1),
                        new StatisticsByDefectTypeItem(
                                DefectType.UPLIFTED, 1),
                        new StatisticsByDefectTypeItem(
                                DefectType.BLOCKED, 1),
                        new StatisticsByDefectTypeItem(
                                DefectType.MISSING_SUSPECTED, 0),
                        new StatisticsByDefectTypeItem(
                                DefectType.INSTALLATION_INVALID, 0),
                        new StatisticsByDefectTypeItem(
                                DefectType.UNKNOWN_ANOMALY, 0)));
    }

    private static StatisticsByAreaResponse areaResponse() {
        return new StatisticsByAreaResponse(
                FROM,
                TO,
                List.of(
                        new StatisticsByAreaItem(
                                UUID.fromString(
                                        "30000000-0000-4000-8000-000000000001"),
                                "A-11",
                                "Fixture Area A",
                                metrics(1, 1, 0, 5_400)),
                        new StatisticsByAreaItem(
                                UUID.fromString(
                                        "30000000-0000-4000-8000-000000000002"),
                                "B-22",
                                "Fixture Area B",
                                metrics(1, 0, 1, 6_600))),
                0,
                20,
                2,
                1);
    }

    private static StatisticsAggregateMetrics metrics(
            long completed,
            long cancelled,
            long failed,
            long duration
    ) {
        return new StatisticsAggregateMetrics(
                2,
                completed,
                cancelled,
                failed,
                new BigDecimal("50.00"),
                duration,
                3,
                2,
                3);
    }
}
