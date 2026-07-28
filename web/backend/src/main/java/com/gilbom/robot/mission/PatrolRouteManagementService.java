package com.gilbom.robot.mission;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.gilbom.audit.AuditAction;
import com.gilbom.audit.AuditActorType;
import com.gilbom.audit.AuditLogRecord;
import com.gilbom.audit.AuditLogService;
import com.gilbom.audit.AuditRequestContext;
import com.gilbom.audit.AuditResult;
import com.gilbom.audit.AuditSnapshot;
import com.gilbom.audit.AuditTargetType;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.robot.data.api.response.PatrolCatalogDtos.PatrolRouteCreateLifecycleStatus;
import com.gilbom.robot.data.api.response.PatrolCatalogDtos.PatrolRouteCreateRequest;
import com.gilbom.robot.data.api.response.PatrolCatalogDtos.PatrolRouteResponse;

@Service
public class PatrolRouteManagementService {

    private static final Set<String> AUDIT_FIELDS = Set.of(
            "patrolAreaId",
            "routeCode",
            "routeName",
            "routeVersion",
            "geometryType",
            "lifecycleStatus",
            "supersededRouteId"
    );

    private final JdbcTemplate jdbcTemplate;
    private final PatrolCatalogQueryService queryService;
    private final AuditLogService auditLogService;

    public PatrolRouteManagementService(
            JdbcTemplate jdbcTemplate,
            PatrolCatalogQueryService queryService,
            AuditLogService auditLogService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.queryService = queryService;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public PatrolRouteResponse create(
            UUID patrolAreaId,
            PatrolRouteCreateRequest request,
            UUID actorUserId,
            AuditRequestContext requestContext
    ) {
        lockActiveArea(patrolAreaId);
        validateRouteGeometry(
                patrolAreaId,
                request.routeGeometry());

        int routeVersion = nextVersion(
                patrolAreaId,
                request.routeCode());
        UUID supersededRouteId = null;
        if (request.lifecycleStatus()
                == PatrolRouteCreateLifecycleStatus.ACTIVE) {
            supersededRouteId = findActiveRouteId(
                    patrolAreaId,
                    request.routeCode());
            deactivateActiveVersion(
                    patrolAreaId,
                    request.routeCode(),
                    actorUserId);
        }

        UUID patrolRouteId = UUID.randomUUID();
        try {
            jdbcTemplate.update("""
                    INSERT INTO robot.patrol_route (
                        patrol_route_id,
                        patrol_area_id,
                        route_code,
                        route_name,
                        route_version,
                        route_geometry,
                        lifecycle_status,
                        created_by,
                        updated_by
                    ) VALUES (
                        ?,
                        ?,
                        ?,
                        ?,
                        ?,
                        ST_SetSRID(ST_GeomFromGeoJSON(?), 4326),
                        ?,
                        ?,
                        ?
                    )
                    """,
                    patrolRouteId,
                    patrolAreaId,
                    request.routeCode(),
                    request.routeName(),
                    routeVersion,
                    request.routeGeometry().toString(),
                    request.lifecycleStatus().name(),
                    actorUserId,
                    actorUserId);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(
                    ErrorCode.PATROL_ROUTE_VERSION_CONFLICT,
                    exception);
        }

        PatrolRouteResponse created = queryService.findRoute(
                patrolAreaId,
                patrolRouteId);
        auditLogService.appendRequired(new AuditLogRecord(
                requestContext,
                actorUserId,
                AuditActorType.USER,
                AuditAction.PATROL_ROUTE_CREATED.databaseValue(),
                AuditTargetType.PATROL_ROUTE,
                patrolRouteId,
                null,
                snapshot(created, supersededRouteId),
                null,
                AuditResult.SUCCESS));
        return created;
    }

    private void lockActiveArea(UUID patrolAreaId) {
        String lifecycleStatus = jdbcTemplate.query("""
                        SELECT lifecycle_status
                        FROM robot.patrol_area
                        WHERE patrol_area_id = ?
                        FOR UPDATE
                        """,
                resultSet -> resultSet.next()
                        ? resultSet.getString("lifecycle_status")
                        : null,
                patrolAreaId);
        if (!"ACTIVE".equals(lifecycleStatus)) {
            throw new BusinessException(ErrorCode.PATROL_AREA_NOT_FOUND);
        }
    }

    private void validateRouteGeometry(
            UUID patrolAreaId,
            JsonNode routeGeometry
    ) {
        if (!hasSupportedShape(routeGeometry)) {
            throw invalidGeometry();
        }

        RouteValidation validation = jdbcTemplate.queryForObject("""
                WITH candidate AS (
                    SELECT ST_SetSRID(
                        ST_GeomFromGeoJSON(?),
                        4326
                    ) AS route_geometry
                )
                SELECT
                    GeometryType(candidate.route_geometry) IN (
                        'LINESTRING',
                        'MULTILINESTRING'
                    )
                    AND NOT ST_IsEmpty(candidate.route_geometry)
                    AND ST_IsValid(candidate.route_geometry)
                    AND ST_Length(candidate.route_geometry) > 0
                    AS geometry_valid,
                    ST_CoveredBy(
                        candidate.route_geometry,
                        area.boundary
                    ) AS inside_area
                FROM candidate
                JOIN robot.patrol_area area
                  ON area.patrol_area_id = ?
                """,
                (resultSet, rowNumber) -> new RouteValidation(
                        resultSet.getBoolean("geometry_valid"),
                        resultSet.getBoolean("inside_area")),
                routeGeometry.toString(),
                patrolAreaId);
        if (validation == null || !validation.geometryValid()) {
            throw invalidGeometry();
        }
        if (!validation.insideArea()) {
            throw new BusinessException(
                    ErrorCode.PATROL_ROUTE_OUTSIDE_AREA);
        }
    }

    private int nextVersion(
            UUID patrolAreaId,
            String routeCode
    ) {
        Integer version = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(route_version), 0) + 1
                FROM robot.patrol_route
                WHERE patrol_area_id = ?
                  AND route_code = ?
                """,
                Integer.class,
                patrolAreaId,
                routeCode);
        if (version == null) {
            throw new IllegalStateException(
                    "Patrol route version query returned no value");
        }
        return version;
    }

    private UUID findActiveRouteId(
            UUID patrolAreaId,
            String routeCode
    ) {
        return jdbcTemplate.query("""
                        SELECT patrol_route_id
                        FROM robot.patrol_route
                        WHERE patrol_area_id = ?
                          AND route_code = ?
                          AND lifecycle_status = 'ACTIVE'
                        """,
                resultSet -> resultSet.next()
                        ? resultSet.getObject(
                                "patrol_route_id",
                                UUID.class)
                        : null,
                patrolAreaId,
                routeCode);
    }

    private void deactivateActiveVersion(
            UUID patrolAreaId,
            String routeCode,
            UUID actorUserId
    ) {
        jdbcTemplate.update("""
                UPDATE robot.patrol_route
                SET lifecycle_status = 'INACTIVE',
                    updated_by = ?,
                    updated_at = NOW()
                WHERE patrol_area_id = ?
                  AND route_code = ?
                  AND lifecycle_status = 'ACTIVE'
                """,
                actorUserId,
                patrolAreaId,
                routeCode);
    }

    private static boolean hasSupportedShape(JsonNode geometry) {
        if (geometry == null || !geometry.isObject()) {
            return false;
        }
        JsonNode type = geometry.get("type");
        JsonNode coordinates = geometry.get("coordinates");
        if (type == null || !type.isTextual()
                || coordinates == null || !coordinates.isArray()) {
            return false;
        }
        return switch (type.textValue()) {
            case "LineString" -> isLineString(coordinates);
            case "MultiLineString" -> isMultiLineString(coordinates);
            default -> false;
        };
    }

    private static boolean isMultiLineString(JsonNode coordinates) {
        if (coordinates.isEmpty()) {
            return false;
        }
        for (JsonNode line : coordinates) {
            if (!isLineString(line)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLineString(JsonNode coordinates) {
        if (!coordinates.isArray() || coordinates.size() < 2) {
            return false;
        }
        for (JsonNode position : coordinates) {
            if (!isPosition(position)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPosition(JsonNode position) {
        if (!position.isArray() || position.size() < 2
                || !position.get(0).isNumber()
                || !position.get(1).isNumber()) {
            return false;
        }
        double longitude = position.get(0).doubleValue();
        double latitude = position.get(1).doubleValue();
        return Double.isFinite(longitude)
                && Double.isFinite(latitude)
                && longitude >= -180
                && longitude <= 180
                && latitude >= -90
                && latitude <= 90;
    }

    private static AuditSnapshot snapshot(
            PatrolRouteResponse route,
            UUID supersededRouteId
    ) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("patrolAreaId", route.patrolAreaId());
        values.put("routeCode", route.routeCode());
        values.put("routeName", route.routeName());
        values.put("routeVersion", route.routeVersion());
        values.put(
                "geometryType",
                route.routeGeometry().path("type").asText());
        values.put("lifecycleStatus", route.lifecycleStatus());
        values.put("supersededRouteId", supersededRouteId);
        return AuditSnapshot.allow(AUDIT_FIELDS, values);
    }

    private static BusinessException invalidGeometry() {
        return new BusinessException(
                ErrorCode.PATROL_ROUTE_GEOMETRY_INVALID);
    }

    private record RouteValidation(
            boolean geometryValid,
            boolean insideArea
    ) {
    }
}
