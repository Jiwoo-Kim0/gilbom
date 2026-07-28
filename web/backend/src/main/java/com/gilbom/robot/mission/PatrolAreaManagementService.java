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
import com.gilbom.robot.data.api.response.PatrolCatalogDtos.PatrolAreaCreateRequest;
import com.gilbom.robot.data.api.response.PatrolCatalogDtos.PatrolAreaResponse;

@Service
public class PatrolAreaManagementService {

    private static final Set<String> AUDIT_FIELDS = Set.of(
            "areaCode",
            "areaName",
            "boundaryType",
            "lifecycleStatus"
    );

    private final JdbcTemplate jdbcTemplate;
    private final PatrolCatalogQueryService queryService;
    private final AuditLogService auditLogService;

    public PatrolAreaManagementService(
            JdbcTemplate jdbcTemplate,
            PatrolCatalogQueryService queryService,
            AuditLogService auditLogService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.queryService = queryService;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public PatrolAreaResponse create(
            PatrolAreaCreateRequest request,
            UUID actorUserId,
            AuditRequestContext requestContext
    ) {
        validateBoundary(request.boundary());

        UUID patrolAreaId = UUID.randomUUID();
        try {
            jdbcTemplate.update("""
                    INSERT INTO robot.patrol_area (
                        patrol_area_id,
                        area_code,
                        area_name,
                        boundary,
                        lifecycle_status,
                        created_by,
                        updated_by
                    ) VALUES (
                        ?,
                        ?,
                        ?,
                        ST_SetSRID(ST_GeomFromGeoJSON(?), 4326),
                        ?,
                        ?,
                        ?
                    )
                    """,
                    patrolAreaId,
                    request.areaCode(),
                    request.areaName(),
                    request.boundary().toString(),
                    request.lifecycleStatus().name(),
                    actorUserId,
                    actorUserId);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(
                    ErrorCode.PATROL_AREA_CODE_CONFLICT,
                    exception);
        }

        PatrolAreaResponse created = queryService.findArea(patrolAreaId);
        auditLogService.appendRequired(new AuditLogRecord(
                requestContext,
                actorUserId,
                AuditActorType.USER,
                AuditAction.PATROL_AREA_CREATED.databaseValue(),
                AuditTargetType.PATROL_AREA,
                patrolAreaId,
                null,
                snapshot(created),
                null,
                AuditResult.SUCCESS));
        return created;
    }

    private void validateBoundary(JsonNode boundary) {
        if (!hasSupportedShape(boundary)) {
            throw invalidGeometry();
        }

        Boolean valid = jdbcTemplate.queryForObject("""
                WITH candidate AS (
                    SELECT ST_SetSRID(
                        ST_GeomFromGeoJSON(?),
                        4326
                    ) AS boundary
                )
                SELECT
                    GeometryType(boundary) IN (
                        'POLYGON',
                        'MULTIPOLYGON'
                    )
                    AND NOT ST_IsEmpty(boundary)
                    AND ST_IsValid(boundary)
                    AND ST_CoveredBy(
                        boundary,
                        ST_MakeEnvelope(-180, -90, 180, 90, 4326)
                    )
                FROM candidate
                """,
                Boolean.class,
                boundary.toString());
        if (!Boolean.TRUE.equals(valid)) {
            throw invalidGeometry();
        }
    }

    private static boolean hasSupportedShape(JsonNode boundary) {
        if (boundary == null || !boundary.isObject()) {
            return false;
        }
        JsonNode type = boundary.get("type");
        JsonNode coordinates = boundary.get("coordinates");
        if (type == null || !type.isTextual()
                || coordinates == null || !coordinates.isArray()) {
            return false;
        }
        return switch (type.textValue()) {
            case "Polygon" -> isPolygon(coordinates);
            case "MultiPolygon" -> isMultiPolygon(coordinates);
            default -> false;
        };
    }

    private static boolean isMultiPolygon(JsonNode coordinates) {
        if (coordinates.isEmpty()) {
            return false;
        }
        for (JsonNode polygon : coordinates) {
            if (!isPolygon(polygon)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPolygon(JsonNode coordinates) {
        if (!coordinates.isArray() || coordinates.isEmpty()) {
            return false;
        }
        for (JsonNode ring : coordinates) {
            if (!isLinearRing(ring)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLinearRing(JsonNode ring) {
        if (!ring.isArray() || ring.size() < 4) {
            return false;
        }
        for (JsonNode position : ring) {
            if (!isPosition(position)) {
                return false;
            }
        }
        return samePosition(ring.get(0), ring.get(ring.size() - 1));
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

    private static boolean samePosition(JsonNode first, JsonNode last) {
        return Double.compare(
                first.get(0).doubleValue(),
                last.get(0).doubleValue()) == 0
                && Double.compare(
                        first.get(1).doubleValue(),
                        last.get(1).doubleValue()) == 0;
    }

    private static AuditSnapshot snapshot(PatrolAreaResponse area) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("areaCode", area.areaCode());
        values.put("areaName", area.areaName());
        values.put("boundaryType", area.boundary().path("type").asText());
        values.put("lifecycleStatus", area.lifecycleStatus());
        return AuditSnapshot.allow(AUDIT_FIELDS, values);
    }

    private static BusinessException invalidGeometry() {
        return new BusinessException(
                ErrorCode.PATROL_AREA_GEOMETRY_INVALID);
    }

}
