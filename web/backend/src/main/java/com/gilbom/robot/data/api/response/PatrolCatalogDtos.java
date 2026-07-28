package com.gilbom.robot.data.api.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class PatrolCatalogDtos {

    private PatrolCatalogDtos() {
    }

    public enum PatrolAreaCreateLifecycleStatus {
        ACTIVE,
        INACTIVE
    }

    public record PatrolAreaCreateRequest(
            @NotBlank @Size(max = 50) String areaCode,
            @NotBlank @Size(max = 100) String areaName,
            @NotNull JsonNode boundary,
            PatrolAreaCreateLifecycleStatus lifecycleStatus
    ) {

        public PatrolAreaCreateRequest {
            areaCode = normalize(areaCode);
            areaName = normalize(areaName);
            lifecycleStatus = lifecycleStatus == null
                    ? PatrolAreaCreateLifecycleStatus.ACTIVE
                    : lifecycleStatus;
        }
    }

    public record PatrolAreaListResponse(
            List<PatrolAreaResponse> areas
    ) {

        public PatrolAreaListResponse {
            areas = List.copyOf(areas);
        }
    }

    public record PatrolAreaResponse(
            UUID patrolAreaId,
            String areaCode,
            String areaName,
            JsonNode boundary,
            String lifecycleStatus,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record PatrolRouteListResponse(
            List<PatrolRouteResponse> routes
    ) {

        public PatrolRouteListResponse {
            routes = List.copyOf(routes);
        }
    }

    public record PatrolRouteResponse(
            UUID patrolRouteId,
            UUID patrolAreaId,
            String routeCode,
            String routeName,
            int routeVersion,
            JsonNode routeGeometry,
            String lifecycleStatus,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim();
    }
}
