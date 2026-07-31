package com.gilbom.robot.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
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
import com.gilbom.robot.api.RobotTelemetrySeriesController;
import com.gilbom.robot.data.api.response.RobotTelemetrySeriesPointResponse;
import com.gilbom.robot.data.api.response.RobotTelemetrySeriesResponse;
import com.gilbom.robot.telemetry.RobotTelemetrySeriesService;
import com.gilbom.security.config.SecurityConfig;
import com.gilbom.security.web.SecurityErrorResponseWriter;
import com.gilbom.security.web.SecurityHeadersFilter;

@WebMvcTest(controllers = RobotTelemetrySeriesController.class)
@Import({
        SecurityConfig.class,
        SecurityErrorResponseWriter.class,
        SecurityHeadersFilter.class,
        RequestIdFilter.class,
        RequestBodySizeLimitFilter.class,
        GlobalExceptionHandler.class,
        AuthenticatedUserIdResolver.class
})
class RobotTelemetrySeriesControllerTest {

    private static final UUID USER_ID =
            UUID.fromString("28000000-0000-4000-8000-000000000001");
    private static final UUID ROBOT_ID =
            UUID.fromString("28000000-0000-4000-8000-000000000002");
    private static final Instant FROM =
            Instant.parse("2026-07-30T00:00:00Z");
    private static final Instant TO =
            Instant.parse("2026-07-31T00:00:00Z");
    private static final SimpleGrantedAuthority ROBOT_STATUS_VIEW =
            new SimpleGrantedAuthority("ROBOT_STATUS_VIEW");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RobotTelemetrySeriesService seriesService;

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
    void returnsFiveMinuteTelemetrySeriesByDefault() throws Exception {
        when(seriesService.find(USER_ID, ROBOT_ID, FROM, TO, "5m"))
                .thenReturn(response());

        mockMvc.perform(get(
                        "/api/v1/robots/{robotId}/telemetry-series",
                        ROBOT_ID)
                        .param("from", "2026-07-30T09:00:00+09:00")
                        .param("to", "2026-07-31T09:00:00+09:00")
                        .with(user(USER_ID.toString())
                                .authorities(ROBOT_STATUS_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.robotId", is(
                        ROBOT_ID.toString())))
                .andExpect(jsonPath("$.interval", is("5m")))
                .andExpect(jsonPath("$.points", hasSize(1)))
                .andExpect(jsonPath(
                        "$.points[0].averageBatteryPercent",
                        is(70.0)))
                .andExpect(jsonPath(
                        "$.points[0].maximumJetsonTemperatureC",
                        is(70.0)))
                .andExpect(jsonPath(
                        "$.points[0].gpsValidRatePercent",
                        is(50.0)));

        verify(seriesService).find(USER_ID, ROBOT_ID, FROM, TO, "5m");
    }

    @Test
    void rejectsRequestWithoutRobotStatusViewPermission()
            throws Exception {
        mockMvc.perform(get(
                        "/api/v1/robots/{robotId}/telemetry-series",
                        ROBOT_ID)
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .with(user(USER_ID.toString())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("AUTH_FORBIDDEN")));

        verifyNoInteractions(seriesService);
    }

    @Test
    void rejectsMissingPeriodBoundary() throws Exception {
        mockMvc.perform(get(
                        "/api/v1/robots/{robotId}/telemetry-series",
                        ROBOT_ID)
                        .param("from", FROM.toString())
                        .with(user(USER_ID.toString())
                                .authorities(ROBOT_STATUS_VIEW)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code",
                        is("COMMON_INVALID_REQUEST")));

        verifyNoInteractions(seriesService);
    }

    @Test
    void mapsUnsupportedIntervalToBadRequest() throws Exception {
        when(seriesService.find(USER_ID, ROBOT_ID, FROM, TO, "30m"))
                .thenThrow(new BusinessException(
                        ErrorCode.COMMON_INVALID_REQUEST));

        mockMvc.perform(get(
                        "/api/v1/robots/{robotId}/telemetry-series",
                        ROBOT_ID)
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .param("interval", "30m")
                        .with(user(USER_ID.toString())
                                .authorities(ROBOT_STATUS_VIEW)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code",
                        is("COMMON_INVALID_REQUEST")));
    }

    private RobotTelemetrySeriesResponse response() {
        return new RobotTelemetrySeriesResponse(
                ROBOT_ID,
                "RBT-SERIES-001",
                "시계열 로봇",
                FROM,
                TO,
                "5m",
                List.of(new RobotTelemetrySeriesPointResponse(
                        Instant.parse("2026-07-30T00:00:00Z"),
                        2,
                        2,
                        new BigDecimal("70.00"),
                        new BigDecimal("60.00"),
                        new BigDecimal("60.00"),
                        new BigDecimal("70.00"),
                        new BigDecimal("2.00"),
                        new BigDecimal("3.00"),
                        1,
                        1,
                        new BigDecimal("50.00"))));
    }
}
