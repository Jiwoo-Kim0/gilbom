package com.gilbom.robot.mission;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.robot.data.api.response.PatrolCatalogDtos.PatrolAreaListResponse;
import com.gilbom.robot.data.api.response.PatrolCatalogDtos.PatrolAreaResponse;
import com.gilbom.robot.data.api.response.PatrolCatalogDtos.PatrolRouteListResponse;
import com.gilbom.robot.data.api.response.PatrolCatalogDtos.PatrolRouteResponse;

@Service
public class PatrolCatalogQueryService {

    private static final String AREA_COLUMNS = """
            patrol_area_id,
            area_code,
            area_name,
            ST_AsGeoJSON(boundary) AS boundary_geojson,
            lifecycle_status,
            created_at,
            updated_at
            """;

    private static final String ROUTE_COLUMNS = """
            patrol_route_id,
            patrol_area_id,
            route_code,
            route_name,
            route_version,
            ST_AsGeoJSON(route_geometry) AS route_geojson,
            lifecycle_status,
            created_at,
            updated_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PatrolCatalogQueryService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PatrolAreaListResponse findAreas() {
        List<PatrolAreaResponse> areas = jdbcTemplate.query("""
                SELECT
                """ + AREA_COLUMNS + """
                FROM robot.patrol_area
                ORDER BY
                    CASE lifecycle_status
                        WHEN 'ACTIVE' THEN 0
                        WHEN 'INACTIVE' THEN 1
                        ELSE 2
                    END,
                    area_code ASC,
                    patrol_area_id ASC
                """,
                (resultSet, rowNumber) -> mapArea(resultSet));
        return new PatrolAreaListResponse(areas);
    }

    @Transactional(readOnly = true)
    public PatrolAreaResponse findArea(UUID patrolAreaId) {
        List<PatrolAreaResponse> areas = jdbcTemplate.query("""
                SELECT
                """ + AREA_COLUMNS + """
                FROM robot.patrol_area
                WHERE patrol_area_id = ?
                """,
                (resultSet, rowNumber) -> mapArea(resultSet),
                patrolAreaId);
        if (areas.isEmpty()) {
            throw new BusinessException(ErrorCode.PATROL_AREA_NOT_FOUND);
        }
        return areas.getFirst();
    }

    @Transactional(readOnly = true)
    public PatrolRouteListResponse findRoutes(UUID patrolAreaId) {
        requireArea(patrolAreaId);
        List<PatrolRouteResponse> routes = jdbcTemplate.query("""
                SELECT
                """ + ROUTE_COLUMNS + """
                FROM robot.patrol_route
                WHERE patrol_area_id = ?
                ORDER BY
                    route_code ASC,
                    route_version DESC,
                    patrol_route_id ASC
                """,
                (resultSet, rowNumber) -> mapRoute(resultSet),
                patrolAreaId);
        return new PatrolRouteListResponse(routes);
    }

    private void requireArea(UUID patrolAreaId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM robot.patrol_area
                    WHERE patrol_area_id = ?
                )
                """,
                Boolean.class,
                patrolAreaId);
        if (!Boolean.TRUE.equals(exists)) {
            throw new BusinessException(ErrorCode.PATROL_AREA_NOT_FOUND);
        }
    }

    private PatrolAreaResponse mapArea(ResultSet resultSet)
            throws SQLException {
        return new PatrolAreaResponse(
                resultSet.getObject("patrol_area_id", UUID.class),
                resultSet.getString("area_code"),
                resultSet.getString("area_name"),
                geometry(resultSet.getString("boundary_geojson")),
                resultSet.getString("lifecycle_status"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"));
    }

    private PatrolRouteResponse mapRoute(ResultSet resultSet)
            throws SQLException {
        return new PatrolRouteResponse(
                resultSet.getObject("patrol_route_id", UUID.class),
                resultSet.getObject("patrol_area_id", UUID.class),
                resultSet.getString("route_code"),
                resultSet.getString("route_name"),
                resultSet.getInt("route_version"),
                geometry(resultSet.getString("route_geojson")),
                resultSet.getString("lifecycle_status"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"));
    }

    private JsonNode geometry(String geoJson) {
        try {
            return objectMapper.readTree(geoJson);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    ErrorCode.COMMON_INTERNAL_ERROR,
                    exception);
        }
    }

    private static java.time.Instant instant(
            ResultSet resultSet,
            String column
    ) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }
}
