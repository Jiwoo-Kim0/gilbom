package com.gilbom.robot.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.common.web.GlobalExceptionHandler;
import com.gilbom.common.web.RequestBodySizeLimitFilter;
import com.gilbom.common.web.RequestIdFilter;
import com.gilbom.robot.api.RobotTelemetryStatisticsController;
import com.gilbom.robot.data.api.response.RobotTelemetryStatisticsResponse;
import com.gilbom.robot.telemetry.RobotTelemetryStatisticsService;
import com.gilbom.security.config.SecurityConfig;
import com.gilbom.security.web.SecurityErrorResponseWriter;
import com.gilbom.security.web.SecurityHeadersFilter;

@WebMvcTest(controllers = RobotTelemetryStatisticsController.class)
@Import({
        SecurityConfig.class,
        SecurityErrorResponseWriter.class,
        SecurityHeadersFilter.class,
        RequestIdFilter.class,
        RequestBodySizeLimitFilter.class,
        GlobalExceptionHandler.class,
        AuthenticatedUserIdResolver.class
})
class RobotTelemetryStatisticsControllerTest {

    private static final UUID USER_ID =
            UUID.fromString("26000000-0000-4000-8000-000000000001");
    private static final UUID ROBOT_ID =
            UUID.fromString("26000000-0000-4000-8000-000000000002");
    private static final Instant FROM =
            Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant TO =
            Instant.parse("2026-07-31T00:00:00Z");
    private static final SimpleGrantedAuthority ROBOT_STATUS_VIEW =
            new SimpleGrantedAuthority("ROBOT_STATUS_VIEW");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RobotTelemetryStatisticsService statisticsService;

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
    void returnsTelemetryStatisticsWithRobotStatusViewPermission()
            throws Exception {
        when(statisticsService.find(USER_ID, ROBOT_ID, FROM, TO))
                .thenReturn(response());

        mockMvc.perform(get(
                        "/api/v1/robots/{robotId}/telemetry-statistics",
                        ROBOT_ID)
                        .param("from", "2026-07-01T09:00:00+09:00")
                        .param("to", "2026-07-31T09:00:00+09:00")
                        .with(user(USER_ID.toString())
                                .authorities(ROBOT_STATUS_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.robotId", is(
                        ROBOT_ID.toString())))
                .andExpect(jsonPath("$.sampleCount", is(3)))
                .andExpect(jsonPath("$.averageBatteryPercent", is(70.0)))
                .andExpect(jsonPath("$.maximumJetsonTemperatureC",
                        is(70.0)))
                .andExpect(jsonPath("$.gpsValidRatePercent", is(66.67)));
    }

    @Test
    void rejectsRequestWithoutRobotStatusViewPermission()
            throws Exception {
        mockMvc.perform(get(
                        "/api/v1/robots/{robotId}/telemetry-statistics",
                        ROBOT_ID)
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .with(user(USER_ID.toString())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("AUTH_FORBIDDEN")));

        verifyNoInteractions(statisticsService);
    }

    @Test
    void rejectsMissingPeriodBoundary() throws Exception {
        mockMvc.perform(get(
                        "/api/v1/robots/{robotId}/telemetry-statistics",
                        ROBOT_ID)
                        .param("from", FROM.toString())
                        .with(user(USER_ID.toString())
                                .authorities(ROBOT_STATUS_VIEW)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code",
                        is("COMMON_INVALID_REQUEST")));

        verifyNoInteractions(statisticsService);
    }

    @Test
    void mapsInvalidTelemetryPeriodToUnprocessableEntity()
            throws Exception {
        when(statisticsService.find(USER_ID, ROBOT_ID, TO, FROM))
                .thenThrow(new BusinessException(
                        ErrorCode.STATISTICS_PERIOD_INVALID));

        mockMvc.perform(get(
                        "/api/v1/robots/{robotId}/telemetry-statistics",
                        ROBOT_ID)
                        .param("from", TO.toString())
                        .param("to", FROM.toString())
                        .with(user(USER_ID.toString())
                                .authorities(ROBOT_STATUS_VIEW)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code",
                        is("STATISTICS_PERIOD_INVALID")));
    }

    private RobotTelemetryStatisticsResponse response() {
        return new RobotTelemetryStatisticsResponse(
                ROBOT_ID,
                "RBT-STAT-001",
                "통계 로봇",
                FROM,
                TO,
                3,
                2,
                new BigDecimal("70.00"),
                new BigDecimal("60.00"),
                new BigDecimal("60.00"),
                new BigDecimal("70.00"),
                new BigDecimal("2.00"),
                new BigDecimal("3.00"),
                2,
                1,
                new BigDecimal("66.67"),
                Instant.parse("2026-07-15T01:00:00Z"));
    }
}
