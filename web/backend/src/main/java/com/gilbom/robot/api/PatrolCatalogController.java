package com.gilbom.robot.api;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gilbom.audit.AuditRequestContext;
import com.gilbom.auth.AuthenticatedUserIdResolver;
import com.gilbom.robot.data.api.response.PatrolCatalogDtos.PatrolAreaCreateRequest;
import com.gilbom.robot.data.api.response.PatrolCatalogDtos.PatrolAreaListResponse;
import com.gilbom.robot.data.api.response.PatrolCatalogDtos.PatrolAreaResponse;
import com.gilbom.robot.data.api.response.PatrolCatalogDtos.PatrolAreaStatusChangeRequest;
import com.gilbom.robot.data.api.response.PatrolCatalogDtos.PatrolAreaUpdateRequest;
import com.gilbom.robot.data.api.response.PatrolCatalogDtos.PatrolRouteCreateRequest;
import com.gilbom.robot.data.api.response.PatrolCatalogDtos.PatrolRouteListResponse;
import com.gilbom.robot.data.api.response.PatrolCatalogDtos.PatrolRouteResponse;
import com.gilbom.robot.mission.PatrolAreaManagementService;
import com.gilbom.robot.mission.PatrolCatalogQueryService;
import com.gilbom.robot.mission.PatrolRouteManagementService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/patrol-areas")
public class PatrolCatalogController {

    private final PatrolCatalogQueryService queryService;
    private final PatrolAreaManagementService managementService;
    private final PatrolRouteManagementService routeManagementService;
    private final AuthenticatedUserIdResolver userIdResolver;

    public PatrolCatalogController(
            PatrolCatalogQueryService queryService,
            PatrolAreaManagementService managementService,
            PatrolRouteManagementService routeManagementService,
            AuthenticatedUserIdResolver userIdResolver
    ) {
        this.queryService = queryService;
        this.managementService = managementService;
        this.routeManagementService = routeManagementService;
        this.userIdResolver = userIdResolver;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('MISSION_MANAGE')")
    public ResponseEntity<PatrolAreaResponse> createArea(
            @Valid @RequestBody PatrolAreaCreateRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        PatrolAreaResponse created = managementService.create(
                request,
                userIdResolver.require(authentication),
                AuditRequestContext.from(servletRequest));
        return ResponseEntity
                .created(URI.create(
                        "/api/v1/patrol-areas/"
                                + created.patrolAreaId()))
                .body(created);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('MISSION_VIEW')")
    public PatrolAreaListResponse findAreas() {
        return queryService.findAreas();
    }

    @GetMapping("/{patrolAreaId}")
    @PreAuthorize("hasAuthority('MISSION_VIEW')")
    public PatrolAreaResponse findArea(
            @PathVariable UUID patrolAreaId
    ) {
        return queryService.findArea(patrolAreaId);
    }

    @PatchMapping("/{patrolAreaId}")
    @PreAuthorize("hasAuthority('MISSION_MANAGE')")
    public PatrolAreaResponse updateArea(
            @PathVariable UUID patrolAreaId,
            @Valid @RequestBody PatrolAreaUpdateRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        return managementService.update(
                patrolAreaId,
                request,
                userIdResolver.require(authentication),
                AuditRequestContext.from(servletRequest));
    }

    @PatchMapping("/{patrolAreaId}/status")
    @PreAuthorize("hasAuthority('MISSION_MANAGE')")
    public PatrolAreaResponse changeAreaStatus(
            @PathVariable UUID patrolAreaId,
            @Valid @RequestBody PatrolAreaStatusChangeRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        return managementService.changeStatus(
                patrolAreaId,
                request,
                userIdResolver.require(authentication),
                AuditRequestContext.from(servletRequest));
    }

    @GetMapping("/{patrolAreaId}/routes")
    @PreAuthorize("hasAuthority('MISSION_VIEW')")
    public PatrolRouteListResponse findRoutes(
            @PathVariable UUID patrolAreaId
    ) {
        return queryService.findRoutes(patrolAreaId);
    }

    @GetMapping("/{patrolAreaId}/routes/{patrolRouteId}")
    @PreAuthorize("hasAuthority('MISSION_VIEW')")
    public PatrolRouteResponse findRoute(
            @PathVariable UUID patrolAreaId,
            @PathVariable UUID patrolRouteId
    ) {
        return queryService.findRoute(
                patrolAreaId,
                patrolRouteId);
    }

    @PostMapping("/{patrolAreaId}/routes")
    @PreAuthorize("hasAuthority('MISSION_MANAGE')")
    public ResponseEntity<PatrolRouteResponse> createRoute(
            @PathVariable UUID patrolAreaId,
            @Valid @RequestBody PatrolRouteCreateRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        PatrolRouteResponse created = routeManagementService.create(
                patrolAreaId,
                request,
                userIdResolver.require(authentication),
                AuditRequestContext.from(servletRequest));
        return ResponseEntity
                .created(URI.create(
                        "/api/v1/patrol-areas/"
                                + patrolAreaId
                                + "/routes/"
                                + created.patrolRouteId()))
                .body(created);
    }
}
