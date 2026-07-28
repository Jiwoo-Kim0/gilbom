package com.gilbom.robot.api;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gilbom.robot.data.api.response.PatrolCatalogDtos.PatrolAreaListResponse;
import com.gilbom.robot.data.api.response.PatrolCatalogDtos.PatrolAreaResponse;
import com.gilbom.robot.data.api.response.PatrolCatalogDtos.PatrolRouteListResponse;
import com.gilbom.robot.mission.PatrolCatalogQueryService;

@RestController
@RequestMapping("/api/v1/patrol-areas")
public class PatrolCatalogController {

    private final PatrolCatalogQueryService queryService;

    public PatrolCatalogController(
            PatrolCatalogQueryService queryService
    ) {
        this.queryService = queryService;
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
