package com.gilbom.robot.data.api.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.constraints.AssertTrue;
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

    public enum PatrolAreaLifecycleStatus {
        ACTIVE,
        INACTIVE,
        ARCHIVED
    }

    public enum PatrolRouteCreateLifecycleStatus {
        DRAFT,
        ACTIVE
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

    public record PatrolAreaUpdateRequest(
            @Size(min = 1, max = 50) String areaCode,
            @Size(min = 1, max = 100) String areaName,
            JsonNode boundary
    ) {

        public PatrolAreaUpdateRequest {
            areaCode = normalize(areaCode);
            areaName = normalize(areaName);
        }

        @AssertTrue(message = "at least one patrol area field must be provided")
        @JsonIgnore
        public boolean isPatchPresent() {
            return areaCode != null
                    || areaName != null
                    || boundary != null;
        }
    }

    public record PatrolAreaStatusChangeRequest(
            @NotNull PatrolAreaLifecycleStatus lifecycleStatus
    ) {
    }

    public record PatrolAreaListResponse(
            List<PatrolAreaResponse> areas
    ) {

        public PatrolAreaListResponse {
            areas = List.copyOf(areas);
        }
    }

    public record PatrolAreaCodeAvailabilityResponse(
            String areaCode,
            boolean available
    ) {
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

    public record PatrolRouteCreateRequest(
            @NotBlank @Size(max = 50) String routeCode,
            @NotBlank @Size(max = 100) String routeName,
            @NotNull JsonNode routeGeometry,
            PatrolRouteCreateLifecycleStatus lifecycleStatus
    ) {

        public PatrolRouteCreateRequest {
            routeCode = normalize(routeCode);
            routeName = normalize(routeName);
            lifecycleStatus = lifecycleStatus == null
                    ? PatrolRouteCreateLifecycleStatus.ACTIVE
                    : lifecycleStatus;
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
