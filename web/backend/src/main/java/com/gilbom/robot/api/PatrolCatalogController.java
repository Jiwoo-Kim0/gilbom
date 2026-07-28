package com.gilbom.robot.api;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
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
import com.gilbom.robot.data.api.response.PatrolCatalogDtos.PatrolRouteListResponse;
import com.gilbom.robot.mission.PatrolAreaManagementService;
import com.gilbom.robot.mission.PatrolCatalogQueryService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/patrol-areas")
public class PatrolCatalogController {

    private final PatrolCatalogQueryService queryService;
    private final PatrolAreaManagementService managementService;
    private final AuthenticatedUserIdResolver userIdResolver;

    public PatrolCatalogController(
            PatrolCatalogQueryService queryService,
            PatrolAreaManagementService managementService,
            AuthenticatedUserIdResolver userIdResolver
    ) {
        this.queryService = queryService;
        this.managementService = managementService;
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

    @GetMapping("/{patrolAreaId}/routes")
    @PreAuthorize("hasAuthority('MISSION_VIEW')")
    public PatrolRouteListResponse findRoutes(
            @PathVariable UUID patrolAreaId
    ) {
        return queryService.findRoutes(patrolAreaId);
    }
}
