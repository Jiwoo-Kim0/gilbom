package com.gilbom.robot.api;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gilbom.audit.AuditRequestContext;
import com.gilbom.auth.AuthenticatedUserIdResolver;
import com.gilbom.robot.data.api.response.MissionDtos.MissionCreateRequest;
import com.gilbom.robot.data.api.response.MissionDtos.MissionListResponse;
import com.gilbom.robot.data.api.response.MissionDtos.MissionMapResponse;
import com.gilbom.robot.data.api.response.MissionDtos.MissionResponse;
import com.gilbom.robot.data.api.response.MissionDtos.MissionStatus;
import com.gilbom.robot.data.api.response.MissionDtos.MissionStatusHistoryResponse;
import com.gilbom.robot.data.api.response.MissionDtos.MissionTrajectoryResponse;
import com.gilbom.robot.data.api.response.MissionDtos.MissionUpdateRequest;
import com.gilbom.robot.mission.MissionManagementService;
import com.gilbom.robot.mission.MissionQueryService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Validated
@RestController
@RequestMapping("/api/v1/missions")
public class MissionController {

    private final MissionManagementService managementService;
    private final MissionQueryService queryService;
    private final AuthenticatedUserIdResolver userIdResolver;

    public MissionController(
            MissionManagementService managementService,
            MissionQueryService queryService,
            AuthenticatedUserIdResolver userIdResolver
    ) {
        this.managementService = managementService;
        this.queryService = queryService;
        this.userIdResolver = userIdResolver;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('MISSION_VIEW')")
    public MissionListResponse findMissions(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) UUID robotId,
            @RequestParam(required = false) UUID patrolAreaId,
            @RequestParam(required = false) UUID patrolRouteId,
            @RequestParam(required = false) MissionStatus status,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant startedFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant startedTo,
            Authentication authentication
    ) {
        return queryService.findMissions(
                userIdResolver.require(authentication),
                page,
                size,
                robotId,
                patrolAreaId,
                patrolRouteId,
                status,
                startedFrom,
                startedTo);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('MISSION_MANAGE')")
    public ResponseEntity<MissionResponse> createMission(
            @Valid @RequestBody MissionCreateRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        MissionResponse mission = managementService.create(
                request,
                userIdResolver.require(authentication),
                AuditRequestContext.from(servletRequest));
        return ResponseEntity
                .created(URI.create(
                        "/api/v1/missions/" + mission.missionId()))
                .body(mission);
    }

    @GetMapping("/{missionId}")
    @PreAuthorize("hasAuthority('MISSION_VIEW')")
    public MissionResponse findMission(
            @PathVariable UUID missionId,
            Authentication authentication
    ) {
        return queryService.findMission(
                userIdResolver.require(authentication),
                missionId);
    }

    @PatchMapping("/{missionId}")
    @PreAuthorize("hasAuthority('MISSION_MANAGE')")
    public MissionResponse updateMission(
            @PathVariable UUID missionId,
            @Valid @RequestBody MissionUpdateRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        return managementService.update(
                missionId,
                request,
                userIdResolver.require(authentication),
                AuditRequestContext.from(servletRequest));
    }

    @DeleteMapping("/{missionId}")
    @PreAuthorize("hasAuthority('MISSION_MANAGE')")
    public ResponseEntity<Void> deleteMission(
            @PathVariable UUID missionId,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        managementService.delete(
                missionId,
                userIdResolver.require(authentication),
                AuditRequestContext.from(servletRequest));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{missionId}/trajectory")
    @PreAuthorize("hasAuthority('MISSION_VIEW')")
    public MissionTrajectoryResponse findTrajectory(
            @PathVariable UUID missionId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant to,
            @RequestParam(defaultValue = "1000")
            @Min(1) @Max(5000) int limit,
            Authentication authentication
    ) {
        return queryService.findTrajectory(
                userIdResolver.require(authentication),
                missionId,
                from,
                to,
                limit);
    }

    @GetMapping("/{missionId}/map")
    @PreAuthorize("hasAuthority('MISSION_VIEW')")
    public MissionMapResponse findMap(
            @PathVariable UUID missionId,
            @RequestParam(defaultValue = "5000")
            @Min(1) @Max(5000) int limit,
            Authentication authentication
    ) {
        return queryService.findMap(
                userIdResolver.require(authentication),
                missionId,
                limit);
    }

    @GetMapping("/{missionId}/history")
    @PreAuthorize("hasAuthority('MISSION_VIEW')")
    public MissionStatusHistoryResponse findHistory(
            @PathVariable UUID missionId,
            Authentication authentication
    ) {
        return queryService.findHistory(
                userIdResolver.require(authentication),
                missionId);
    }
}
