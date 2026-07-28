package com.gilbom.robot.data.api.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public final class PatrolCatalogDtos {

    private PatrolCatalogDtos() {
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
}
