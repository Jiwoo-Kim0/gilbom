package com.gilbom.robot.mission;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
import com.gilbom.robot.data.api.response.PatrolCatalogDtos.PatrolAreaLifecycleStatus;
import com.gilbom.robot.data.api.response.PatrolCatalogDtos.PatrolAreaResponse;
import com.gilbom.robot.data.api.response.PatrolCatalogDtos.PatrolAreaStatusChangeRequest;
import com.gilbom.robot.data.api.response.PatrolCatalogDtos.PatrolAreaUpdateRequest;

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

    @Transactional
    public PatrolAreaResponse update(
            UUID patrolAreaId,
            PatrolAreaUpdateRequest request,
            UUID actorUserId,
            AuditRequestContext requestContext
    ) {
        if (request.boundary() != null) {
            validateBoundary(request.boundary());
        }

        PatrolAreaResponse before = lockArea(patrolAreaId);
        requireEditable(before);
        requireNotInUse(patrolAreaId);
        if (request.boundary() != null) {
            requireRoutesCoveredBy(
                    patrolAreaId,
                    request.boundary());
        }

        List<String> assignments = new ArrayList<>();
        List<Object> parameters = new ArrayList<>();
        if (request.areaName() != null) {
            assignments.add("area_name = ?");
            parameters.add(request.areaName());
        }
        if (request.boundary() != null) {
            assignments.add(
                    "boundary = ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)");
            parameters.add(request.boundary().toString());
        }
        assignments.add("updated_by = ?");
        parameters.add(actorUserId);
        assignments.add("updated_at = NOW()");
        parameters.add(patrolAreaId);

        jdbcTemplate.update("""
                UPDATE robot.patrol_area
                SET
                """ + String.join(",\n", assignments) + """

                WHERE patrol_area_id = ?
                """,
                parameters.toArray());

        PatrolAreaResponse after = queryService.findArea(patrolAreaId);
        appendUpdateAudit(
                patrolAreaId,
                before,
                after,
                actorUserId,
                requestContext);
        return after;
    }

    @Transactional
    public PatrolAreaResponse changeStatus(
            UUID patrolAreaId,
            PatrolAreaStatusChangeRequest request,
            UUID actorUserId,
            AuditRequestContext requestContext
    ) {
        PatrolAreaResponse before = lockArea(patrolAreaId);
        PatrolAreaLifecycleStatus current =
                PatrolAreaLifecycleStatus.valueOf(
                        before.lifecycleStatus());
        PatrolAreaLifecycleStatus target =
                request.lifecycleStatus();
        if (current == target) {
            return before;
        }
        if (!isAllowedTransition(current, target)) {
            throw new BusinessException(
                    ErrorCode.PATROL_AREA_STATE_CONFLICT);
        }
        requireNotInUse(patrolAreaId);

        jdbcTemplate.update("""
                UPDATE robot.patrol_area
                SET lifecycle_status = ?,
                    updated_by = ?,
                    updated_at = NOW()
                WHERE patrol_area_id = ?
                """,
                target.name(),
                actorUserId,
                patrolAreaId);

        PatrolAreaResponse after = queryService.findArea(patrolAreaId);
        appendUpdateAudit(
                patrolAreaId,
                before,
                after,
                actorUserId,
                requestContext);
        return after;
    }

    private PatrolAreaResponse lockArea(UUID patrolAreaId) {
        UUID lockedId = jdbcTemplate.query("""
                SELECT patrol_area_id
                FROM robot.patrol_area
                WHERE patrol_area_id = ?
                FOR UPDATE
                """,
                resultSet -> resultSet.next()
                        ? resultSet.getObject(
                                "patrol_area_id",
                                UUID.class)
                        : null,
                patrolAreaId);
        if (lockedId == null) {
            throw new BusinessException(
                    ErrorCode.PATROL_AREA_NOT_FOUND);
        }
        return queryService.findArea(lockedId);
    }

    private static void requireEditable(PatrolAreaResponse area) {
        if (PatrolAreaLifecycleStatus.ARCHIVED.name()
                .equals(area.lifecycleStatus())) {
            throw new BusinessException(
                    ErrorCode.PATROL_AREA_STATE_CONFLICT);
        }
    }

    private void requireNotInUse(UUID patrolAreaId) {
        Boolean inUse = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM robot.mission
                    WHERE patrol_area_id = ?
                      AND status IN (
                          'CREATED',
                          'READY',
                          'RUNNING',
                          'PAUSED'
                      )
                )
                """,
                Boolean.class,
                patrolAreaId);
        if (Boolean.TRUE.equals(inUse)) {
            throw new BusinessException(
                    ErrorCode.PATROL_AREA_IN_USE);
        }
    }

    private void requireRoutesCoveredBy(
            UUID patrolAreaId,
            JsonNode boundary
    ) {
        Boolean covered = jdbcTemplate.queryForObject("""
                WITH candidate AS (
                    SELECT ST_SetSRID(
                        ST_GeomFromGeoJSON(?),
                        4326
                    ) AS boundary
                )
                SELECT NOT EXISTS (
                    SELECT 1
                    FROM robot.patrol_route,
                         candidate
                    WHERE patrol_area_id = ?
                      AND lifecycle_status IN ('DRAFT', 'ACTIVE')
                      AND NOT ST_CoveredBy(
                          route_geometry,
                          candidate.boundary
                      )
                )
                """,
                Boolean.class,
                boundary.toString(),
                patrolAreaId);
        if (!Boolean.TRUE.equals(covered)) {
            throw new BusinessException(
                    ErrorCode.PATROL_AREA_ROUTE_OUTSIDE_BOUNDARY);
        }
    }

    private static boolean isAllowedTransition(
            PatrolAreaLifecycleStatus current,
            PatrolAreaLifecycleStatus target
    ) {
        return current == PatrolAreaLifecycleStatus.ACTIVE
                && target == PatrolAreaLifecycleStatus.INACTIVE
                || current == PatrolAreaLifecycleStatus.INACTIVE
                && target == PatrolAreaLifecycleStatus.ARCHIVED;
    }

    private void appendUpdateAudit(
            UUID patrolAreaId,
            PatrolAreaResponse before,
            PatrolAreaResponse after,
            UUID actorUserId,
            AuditRequestContext requestContext
    ) {
        auditLogService.appendRequired(new AuditLogRecord(
                requestContext,
                actorUserId,
                AuditActorType.USER,
                AuditAction.PATROL_AREA_UPDATED.databaseValue(),
                AuditTargetType.PATROL_AREA,
                patrolAreaId,
                snapshot(before),
                snapshot(after),
                null,
                AuditResult.SUCCESS));
    }

    private void validateBoundary(JsonNode boundary) {
        if (!hasSupportedShape(boundary)) {
            throw invalidGeometry();
        }
        /*4326: WGS84 GPS 위경도 좌표계, GeoJSON으로 POLYGON 표현 */
        /*WITH절로 복합쿼리 ALIAS화 */
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
                //빈값?
                //형식일치?
                //범위 제한?
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
