package com.gilbom.robot.api;

import java.time.Instant;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gilbom.auth.AuthenticatedUserIdResolver;
import com.gilbom.robot.data.api.response.RobotTelemetryStatisticsResponse;
import com.gilbom.robot.telemetry.RobotTelemetryStatisticsService;

@RestController
@RequestMapping("/api/v1/robots/{robotId}/telemetry-statistics")
public class RobotTelemetryStatisticsController {

    private final RobotTelemetryStatisticsService statisticsService;
    private final AuthenticatedUserIdResolver userIdResolver;

    public RobotTelemetryStatisticsController(
            RobotTelemetryStatisticsService statisticsService,
            AuthenticatedUserIdResolver userIdResolver
    ) {
        this.statisticsService = statisticsService;
        this.userIdResolver = userIdResolver;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ROBOT_STATUS_VIEW')")
    public RobotTelemetryStatisticsResponse find(
            @PathVariable UUID robotId,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant from,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant to,
            Authentication authentication
    ) {
        return statisticsService.find(
                userIdResolver.require(authentication),
                robotId,
                from,
                to);
    }
}
