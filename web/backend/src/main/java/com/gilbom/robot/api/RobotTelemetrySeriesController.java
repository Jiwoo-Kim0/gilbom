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
import com.gilbom.robot.data.api.response.RobotTelemetrySeriesResponse;
import com.gilbom.robot.telemetry.RobotTelemetrySeriesService;

@RestController
@RequestMapping("/api/v1/robots/{robotId}/telemetry-series")
public class RobotTelemetrySeriesController {

    private final RobotTelemetrySeriesService seriesService;
    private final AuthenticatedUserIdResolver userIdResolver;

    public RobotTelemetrySeriesController(
            RobotTelemetrySeriesService seriesService,
            AuthenticatedUserIdResolver userIdResolver
    ) {
        this.seriesService = seriesService;
        this.userIdResolver = userIdResolver;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ROBOT_STATUS_VIEW')")
    public RobotTelemetrySeriesResponse find(
            @PathVariable UUID robotId,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant from,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant to,
            @RequestParam(defaultValue = "5m")
            String interval,
            Authentication authentication
    ) {
        return seriesService.find(
                userIdResolver.require(authentication),
                robotId,
                from,
                to,
                interval);
    }
}
