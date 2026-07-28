package com.gilbom.robot.api;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gilbom.auth.AuthenticatedUserIdResolver;
import com.gilbom.robot.application.RobotQueryService;
import com.gilbom.robot.data.api.response.RobotDetailResponse;
import com.gilbom.robot.data.api.response.RobotListResponse;
import com.gilbom.robot.data.api.response.RobotMapResponse;
import com.gilbom.robot.data.api.response.RobotSummaryResponse;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

@Validated
@RestController
@RequestMapping("/api/v1/robots")
public class RobotQueryController {

    private final RobotQueryService queryService;
    private final AuthenticatedUserIdResolver userIdResolver;

    public RobotQueryController(
            RobotQueryService queryService,
            AuthenticatedUserIdResolver userIdResolver
    ) {
        this.queryService = queryService;
        this.userIdResolver = userIdResolver;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ROBOT_STATUS_VIEW')")
    public RobotListResponse findRobots(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 100) String search,
            Authentication authentication
    ) {
        return queryService.findRobots(
                userIdResolver.require(authentication),
                page,
                size,
                search);
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('ROBOT_STATUS_VIEW')")
    public RobotSummaryResponse summarize(Authentication authentication) {
        return queryService.summarize(
                userIdResolver.require(authentication));
    }

    @GetMapping("/map")
    @PreAuthorize("hasAuthority('ROBOT_STATUS_VIEW')")
    public RobotMapResponse findMapRobots(Authentication authentication) {
        return queryService.findMapRobots(
                userIdResolver.require(authentication));
    }

    @GetMapping("/{robotId}")
    @PreAuthorize("hasAuthority('ROBOT_STATUS_VIEW')")
    public RobotDetailResponse findRobot(
            @PathVariable UUID robotId,
            Authentication authentication
    ) {
        return queryService.findRobot(
                userIdResolver.require(authentication),
                robotId);
    }
}
