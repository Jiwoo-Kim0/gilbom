package com.gilbom.defect;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.defect.DefectDtos.DefectClusterDetailsResponse;
import com.gilbom.defect.DefectDtos.DefectClusterItemResponse;
import com.gilbom.defect.DefectDtos.DefectDetailResponse;
import com.gilbom.defect.DefectDtos.DefectHistoryItemResponse;
import com.gilbom.defect.DefectDtos.DefectImageUrlResponse;
import com.gilbom.defect.DefectDtos.DefectListItemResponse;
import com.gilbom.defect.DefectDtos.DefectMapFeature;
import com.gilbom.defect.DefectDtos.DefectMapGeometry;
import com.gilbom.defect.DefectDtos.DefectMapProperties;
import com.gilbom.defect.DefectDtos.DefectMapResponse;
import com.gilbom.defect.DefectDtos.DefectObservationResponse;
import com.gilbom.defect.DefectDtos.DefectSummaryResponse;
import com.gilbom.defect.DefectDtos.GeoPoint;
import com.gilbom.defect.DefectDtos.PageResponse;

/**
 * Swagger 21.10의 결함 조회 계약을 구현한다. 모든 질의는
 * {@link DefectScopeService#VISIBLE_DEFECT_PREDICATE}로 객체 범위를 함께 좁힌다.
 */
@Service
public class DefectQueryService {

    /**
     * 병합되어 흡수된 결함은 대표 결함으로만 노출한다.
     */
    private static final String NOT_MERGED_AWAY =
            "AND d.merged_into_defect_id IS NULL\n";

    private static final String LIST_COLUMNS = """
            d.defect_id,
            d.incident_code,
            d.asset_type,
            d.block_type,
            d.defect_type,
            d.risk_level,
            d.risk_score,
            d.workflow_status,
            d.resolution,
            ST_X(d.location) AS longitude,
            ST_Y(d.location) AS latitude,
            d.location_accuracy_m,
            d.zone_id,
            d.patrol_area_id,
            d.patrol_route_id,
            d.location_label,
            d.first_seen_at,
            d.last_seen_at,
            d.observation_count,
            d.latest_confidence,
            d.review_required
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final DefectImageUrlIssuer imageUrlIssuer;
    private final DefectAccessService accessService;

    public DefectQueryService(
            NamedParameterJdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            DefectImageUrlIssuer imageUrlIssuer,
            DefectAccessService accessService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.imageUrlIssuer = imageUrlIssuer;
        this.accessService = accessService;
    }

    // ------------------------------------------------------------ summary

    @Transactional(readOnly = true)
    public DefectSummaryResponse summarize(UUID userId, DefectFilter filter) {
        MapSqlParameterSource parameters =
                new MapSqlParameterSource().addValue("userId", userId);
        filter.bind(parameters);

        return jdbcTemplate.queryForObject("""
                SELECT
                    COUNT(*) AS total_defects,
                    COUNT(*) FILTER (
                        WHERE d.workflow_status = 'NEW'
                    ) AS new_defects,
                    COUNT(*) FILTER (
                        WHERE d.workflow_status = 'ACCEPTED'
                    ) AS accepted_defects,
                    COUNT(*) FILTER (
                        WHERE d.workflow_status = 'IN_PROGRESS'
                    ) AS in_progress_defects,
                    COUNT(*) FILTER (
                        WHERE d.workflow_status = 'RESOLVED'
                    ) AS resolved_defects,
                    COUNT(*) FILTER (
                        WHERE d.workflow_status = 'FALSE_POSITIVE'
                    ) AS false_positive_defects,
                    COUNT(*) FILTER (
                        WHERE d.workflow_status = 'REJECTED'
                    ) AS rejected_defects,
                    COUNT(*) FILTER (
                        WHERE d.risk_level = 'CRITICAL'
                    ) AS critical_defects,
                    COUNT(*) FILTER (
                        WHERE d.risk_level = 'HIGH'
                    ) AS high_defects,
                    COUNT(*) FILTER (
                        WHERE d.review_required = TRUE
                    ) AS review_required_defects
                FROM defect.defect d
                WHERE
                """ + DefectScopeService.VISIBLE_DEFECT_PREDICATE
                        + NOT_MERGED_AWAY
                        + filter.toPredicate(),
                parameters,
                (resultSet, rowNumber) -> new DefectSummaryResponse(
                        resultSet.getLong("total_defects"),
                        resultSet.getLong("new_defects"),
                        resultSet.getLong("accepted_defects"),
                        resultSet.getLong("in_progress_defects"),
                        resultSet.getLong("resolved_defects"),
                        resultSet.getLong("false_positive_defects"),
                        resultSet.getLong("rejected_defects"),
                        resultSet.getLong("critical_defects"),
                        resultSet.getLong("high_defects"),
                        resultSet.getLong("review_required_defects")));
    }

    // ---------------------------------------------------------------- map

    @Transactional(readOnly = true)
    public DefectMapResponse findMapDefects(UUID userId, DefectFilter filter) {
        MapSqlParameterSource parameters =
                new MapSqlParameterSource().addValue("userId", userId);
        filter.bind(parameters);

        List<DefectMapFeature> features = jdbcTemplate.query("""
                SELECT
                    d.defect_id,
                    d.incident_code,
                    d.defect_type,
                    d.risk_level,
                    d.workflow_status,
                    d.latest_confidence,
                    ST_X(d.location) AS longitude,
                    ST_Y(d.location) AS latitude
                FROM defect.defect d
                WHERE
                """ + DefectScopeService.VISIBLE_DEFECT_PREDICATE
                        + NOT_MERGED_AWAY
                        + filter.toPredicate() + """
                ORDER BY d.last_seen_at DESC, d.defect_id ASC
                """,
                parameters,
                (resultSet, rowNumber) -> new DefectMapFeature(
                        DefectMapGeometry.point(
                                resultSet.getDouble("longitude"),
                                resultSet.getDouble("latitude")),
                        new DefectMapProperties(
                                resultSet.getObject("defect_id", UUID.class),
                                resultSet.getString("incident_code"),
                                resultSet.getString("defect_type"),
                                resultSet.getString("risk_level"),
                                resultSet.getString("workflow_status"),
                                resultSet.getBigDecimal("latest_confidence"))));
        return new DefectMapResponse(features);
    }

    // ----------------------------------------------------- cluster details

    /**
     * 지도 묶음 목록이 쓰는 값을 **결함 여러 건 몫 한 번에** 준다.
     *
     * <p>예전에는 화면이 결함마다 상세와 이미지 URL을 따로 불러 10건이면 20번이 한꺼번에
     * 나갔다. 그중 하나만 401이어도 전역 처리가 로그인을 끊었다. 질의는 두 번(결함 행,
     * 결함별 대표 이미지)으로 고정된다.
     *
     * <p>보이지 않는 결함은 404를 내지 않고 **결과에서 빠진다.** 여러 건을 한 번에 묻는
     * 요청이라 한 건 때문에 전체가 실패하면 목록이 통째로 비고, 어느 id가 존재하는지
     * 알려주는 탐색 통로가 되지도 않는다.
     */
    @Transactional(readOnly = true)
    public DefectClusterDetailsResponse findClusterDetails(
            UUID userId,
            List<UUID> defectIds
    ) {
        List<UUID> requested = defectIds.stream().distinct().toList();
        if (requested.isEmpty()) {
            return new DefectClusterDetailsResponse(List.of());
        }

        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("defectIds", requested);

        Map<UUID, UUID> imageIdByDefectId = findRepresentativeImageIds(parameters);

        return new DefectClusterDetailsResponse(jdbcTemplate.query("""
                SELECT
                    d.defect_id,
                    d.incident_code,
                    d.location_label,
                    d.zone_id
                FROM defect.defect d
                WHERE
                """ + DefectScopeService.VISIBLE_DEFECT_PREDICATE
                        + NOT_MERGED_AWAY + """
                AND d.defect_id IN (:defectIds)
                ORDER BY d.defect_id ASC
                """,
                parameters,
                (resultSet, rowNumber) -> {
                    UUID defectId = resultSet.getObject("defect_id", UUID.class);
                    // 서명 실패는 그 줄의 사진만 비운다. 목록 전체를 503으로 만들지 않는다.
                    DefectImageUrlIssuer.IssuedImageUrl issued =
                            imageUrlIssuer.issue(imageIdByDefectId.get(defectId))
                                    .orElse(null);
                    return new DefectClusterItemResponse(
                            defectId,
                            resultSet.getString("incident_code"),
                            resultSet.getString("location_label"),
                            resultSet.getString("zone_id"),
                            issued == null ? null : issued.url(),
                            issued == null ? null : issued.expiresAt());
                }));
    }

    /**
     * 결함별 대표 관측의 이미지. 우선순위는 {@link #issueImageUrl}과 같아야 목록의
     * 사진과 상세 카드의 사진이 어긋나지 않는다.
     *
     * <p>{@code DISTINCT ON}이 고른 맨 위 관측에 이미지가 없으면 그 결함은 사진이 없는
     * 것으로 둔다 — 단건 발급이 404를 내는 것과 같은 판정이다.
     */
    private Map<UUID, UUID> findRepresentativeImageIds(
            MapSqlParameterSource parameters
    ) {
        List<ClusterImage> rows = jdbcTemplate.query("""
                SELECT DISTINCT ON (COALESCE(od.merged_into_defect_id, od.defect_id))
                    COALESCE(od.merged_into_defect_id, od.defect_id) AS owner_defect_id,
                    o.image_id
                FROM defect.defect_observation o
                JOIN defect.defect od
                  ON od.defect_id = o.defect_id
                WHERE COALESCE(od.merged_into_defect_id, od.defect_id) IN (:defectIds)
                ORDER BY COALESCE(od.merged_into_defect_id, od.defect_id),
                         o.is_representative DESC,
                         o.confidence DESC,
                         o.created_at DESC
                """,
                parameters,
                (resultSet, rowNumber) -> new ClusterImage(
                        resultSet.getObject("owner_defect_id", UUID.class),
                        resultSet.getObject("image_id", UUID.class)));

        Map<UUID, UUID> imageIds = new LinkedHashMap<>();
        for (ClusterImage row : rows) {
            if (row.imageId() != null) {
                imageIds.put(row.defectId(), row.imageId());
            }
        }
        return imageIds;
    }

    // --------------------------------------------------------------- list

    @Transactional(readOnly = true)
    public PageResponse<DefectListItemResponse> findDefects(
            UUID userId,
            int page,
            int size,
            String sort,
            DefectFilter filter
    ) {
        String orderBy = DefectFilter.toOrderBy(sort);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("size", size)
                .addValue("offset", Math.multiplyExact((long) page, size));
        filter.bind(parameters);

        Long total = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM defect.defect d
                WHERE
                """ + DefectScopeService.VISIBLE_DEFECT_PREDICATE
                        + NOT_MERGED_AWAY
                        + filter.toPredicate(),
                parameters,
                Long.class);

        List<DefectListItemResponse> content = jdbcTemplate.query("""
                SELECT
                """ + LIST_COLUMNS + """
                FROM defect.defect d
                WHERE
                """ + DefectScopeService.VISIBLE_DEFECT_PREDICATE
                        + NOT_MERGED_AWAY
                        + filter.toPredicate()
                        + orderBy + """
                LIMIT :size
                OFFSET :offset
                """,
                parameters,
                (resultSet, rowNumber) -> mapListItem(resultSet));

        long totalElements = total == null ? 0 : total;
        return new PageResponse<>(
                content,
                page,
                size,
                totalElements,
                Math.toIntExact(Math.ceilDiv(totalElements, size)));
    }

    // ------------------------------------------------------------- detail

    @Transactional(readOnly = true)
    public DefectDetailResponse findDefect(UUID userId, UUID defectId) {
        List<DefectDetailResponse> defects = jdbcTemplate.query("""
                SELECT
                """ + LIST_COLUMNS + """
                    ,
                    d.resolution_note,
                    d.risk_rule_version,
                    d.risk_calculated_at,
                    d.merged_into_defect_id,
                    d.created_at,
                    d.updated_at
                FROM defect.defect d
                WHERE d.defect_id = :defectId
                  AND
                """ + DefectScopeService.VISIBLE_DEFECT_PREDICATE,
                Map.of("userId", userId, "defectId", defectId),
                (resultSet, rowNumber) -> mapDetail(resultSet));
        if (defects.isEmpty()) {
            // 미존재와 범위 밖을 구분하지 않아 결함 존재 여부를 숨긴다.
            throw new BusinessException(ErrorCode.DEFECT_NOT_FOUND);
        }
        DefectDetailResponse detail = defects.getFirst();
        return new DefectDetailResponse(
                detail.defect(),
                detail.resolutionNote(),
                detail.riskScore(),
                detail.riskRuleVersion(),
                detail.riskCalculatedAt(),
                detail.mergedIntoDefectId(),
                findMergedDefectIds(defectId),
                detail.createdAt(),
                detail.updatedAt());
    }

    // ------------------------------------------------------- observations

    @Transactional(readOnly = true)
    public PageResponse<DefectObservationResponse> findObservations(
            UUID userId,
            UUID defectId,
            int page,
            int size
    ) {
        requireVisibleDefect(userId, defectId);

        Map<String, Object> parameters = Map.of(
                "defectId", defectId,
                "size", size,
                "offset", Math.multiplyExact((long) page, size));

        Long total = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM defect.defect_observation o
                WHERE
                """ + observationScope(),
                parameters,
                Long.class);

        List<DefectObservationResponse> content = jdbcTemplate.query("""
                SELECT
                    o.event_id,
                    o.defect_id,
                    o.robot_id,
                    r.robot_name,
                    o.location_valid,
                    ST_X(o.location) AS longitude,
                    ST_Y(o.location) AS latitude,
                    o.location_accuracy_m,
                    o.zone_id,
                    o.asset_type,
                    o.block_type,
                    o.detected_defect_type,
                    o.confidence,
                    o.damage_ratio,
                    o.image_id,
                    o.evidence_captured_at,
                    o.image_width,
                    o.image_height,
                    o.bbox_norm,
                    o.model_name,
                    o.model_version,
                    o.is_representative,
                    o.created_at
                FROM defect.defect_observation o
                -- 이름은 표시용이라 없어도 관측은 보여야 한다. 로봇 행이 지워졌거나
                -- 아직 등록되지 않은 관측까지 LEFT JOIN으로 통과시킨다.
                LEFT JOIN robot.robot r
                       ON r.robot_id = o.robot_id
                WHERE
                """ + observationScope() + """
                ORDER BY o.created_at DESC, o.event_id ASC
                LIMIT :size
                OFFSET :offset
                """,
                parameters,
                (resultSet, rowNumber) -> mapObservation(resultSet));

        long totalElements = total == null ? 0 : total;
        return new PageResponse<>(
                content,
                page,
                size,
                totalElements,
                Math.toIntExact(Math.ceilDiv(totalElements, size)));
    }

    // ------------------------------------------------------------ history

    @Transactional(readOnly = true)
    public PageResponse<DefectHistoryItemResponse> findHistory(
            UUID userId,
            UUID defectId,
            int page,
            int size
    ) {
        requireVisibleDefect(userId, defectId);

        Map<String, Object> parameters = Map.of(
                "defectId", defectId,
                "size", size,
                "offset", Math.multiplyExact((long) page, size));

        Long total = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM defect.defect_status_history h
                WHERE h.defect_id = :defectId
                """,
                parameters,
                Long.class);

        List<DefectHistoryItemResponse> content = jdbcTemplate.query("""
                SELECT
                    h.history_id,
                    h.defect_id,
                    h.changed_by,
                    h.change_type,
                    h.before_status,
                    h.after_status,
                    h.before_values,
                    h.after_values,
                    h.reason,
                    h.created_at
                FROM defect.defect_status_history h
                WHERE h.defect_id = :defectId
                ORDER BY h.created_at DESC, h.history_id DESC
                LIMIT :size
                OFFSET :offset
                """,
                parameters,
                (resultSet, rowNumber) -> new DefectHistoryItemResponse(
                        resultSet.getLong("history_id"),
                        resultSet.getObject("defect_id", UUID.class),
                        resultSet.getObject("changed_by", UUID.class),
                        resultSet.getString("change_type"),
                        resultSet.getString("before_status"),
                        resultSet.getString("after_status"),
                        readJsonObject(resultSet.getString("before_values")),
                        readJsonObject(resultSet.getString("after_values")),
                        resultSet.getString("reason"),
                        instant(resultSet, "created_at")));

        long totalElements = total == null ? 0 : total;
        return new PageResponse<>(
                content,
                page,
                size,
                totalElements,
                Math.toIntExact(Math.ceilDiv(totalElements, size)));
    }

    // ---------------------------------------------------------- image url

    @Transactional(readOnly = true)
    public DefectImageUrlResponse issueImageUrl(
            UUID userId,
            UUID defectId,
            UUID observationId
    ) {
        requireVisibleDefect(userId, defectId);

        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("defectId", defectId)
                .addValue("observationId", observationId);
        String observationPredicate = observationId == null
                ? ""
                : "AND o.event_id = :observationId\n";

        List<ImageCandidate> candidates = jdbcTemplate.query("""
                SELECT
                    o.event_id,
                    o.image_id,
                    o.image_width,
                    o.image_height,
                    o.bbox_norm
                FROM defect.defect_observation o
                WHERE
                """ + observationScope()
                        + observationPredicate + """
                ORDER BY o.is_representative DESC,
                         o.confidence DESC,
                         o.created_at DESC
                LIMIT 1
                """,
                parameters,
                (resultSet, rowNumber) -> new ImageCandidate(
                        resultSet.getObject("event_id", UUID.class),
                        resultSet.getObject("image_id", UUID.class),
                        resultSet.getObject("image_width", Integer.class),
                        resultSet.getObject("image_height", Integer.class),
                        readBoundingBox(resultSet.getString("bbox_norm"))));

        if (candidates.isEmpty() || candidates.getFirst().imageId() == null) {
            // 관측이 없거나 업로드 실패로 image_id가 null인 경우(계약 11.3 핀 우선 원칙)다.
            throw new BusinessException(ErrorCode.IMAGE_NOT_FOUND);
        }
        ImageCandidate candidate = candidates.getFirst();
        DefectImageUrlIssuer.IssuedImageUrl issued =
                imageUrlIssuer.issue(candidate.imageId())
                        .orElseThrow(() -> new BusinessException(
                                ErrorCode.S3_UNAVAILABLE));
        return new DefectImageUrlResponse(
                defectId,
                candidate.eventId(),
                candidate.imageId(),
                issued.url(),
                issued.expiresAt(),
                candidate.imageWidth(),
                candidate.imageHeight(),
                candidate.bboxNorm());
    }

    // ------------------------------------------------------------ helpers

    /**
     * 대표 결함과 그 결함에 병합된 결함의 관측을 함께 본다.
     */
    private static String observationScope() {
        return """
                (
                    o.defect_id = :defectId
                    OR o.defect_id IN (
                        SELECT merged.defect_id
                        FROM defect.defect merged
                        WHERE merged.merged_into_defect_id = :defectId
                    )
                )
                """;
    }

    private void requireVisibleDefect(UUID userId, UUID defectId) {
        if (!accessService.canView(userId, defectId)) {
            throw new BusinessException(ErrorCode.DEFECT_NOT_FOUND);
        }
    }

    private List<UUID> findMergedDefectIds(UUID defectId) {
        return jdbcTemplate.queryForList("""
                SELECT merged.defect_id
                FROM defect.defect merged
                WHERE merged.merged_into_defect_id = :defectId
                ORDER BY merged.defect_id ASC
                """,
                Map.of("defectId", defectId),
                UUID.class);
    }

    private static DefectListItemResponse mapListItem(ResultSet resultSet)
            throws SQLException {
        return new DefectListItemResponse(
                resultSet.getObject("defect_id", UUID.class),
                resultSet.getString("incident_code"),
                resultSet.getString("asset_type"),
                resultSet.getString("block_type"),
                resultSet.getString("defect_type"),
                resultSet.getString("risk_level"),
                resultSet.getBigDecimal("risk_score"),
                resultSet.getString("workflow_status"),
                resultSet.getString("resolution"),
                geoPoint(resultSet),
                nullableDouble(resultSet, "location_accuracy_m"),
                resultSet.getString("zone_id"),
                resultSet.getObject("patrol_area_id", UUID.class),
                resultSet.getObject("patrol_route_id", UUID.class),
                resultSet.getString("location_label"),
                // storage 스키마 연동 전에는 대표 이미지 URL을 즉시 발급하지 않고
                // 전용 endpoint(GET /{defectId}/image-url)로만 발급한다.
                null,
                instant(resultSet, "first_seen_at"),
                instant(resultSet, "last_seen_at"),
                resultSet.getInt("observation_count"),
                resultSet.getBigDecimal("latest_confidence"),
                resultSet.getBoolean("review_required"));
    }

    private DefectDetailResponse mapDetail(ResultSet resultSet)
            throws SQLException {
        return new DefectDetailResponse(
                mapListItem(resultSet),
                resultSet.getString("resolution_note"),
                resultSet.getBigDecimal("risk_score"),
                resultSet.getString("risk_rule_version"),
                instant(resultSet, "risk_calculated_at"),
                resultSet.getObject("merged_into_defect_id", UUID.class),
                List.of(),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"));
    }

    private DefectObservationResponse mapObservation(ResultSet resultSet)
            throws SQLException {
        boolean locationValid = resultSet.getBoolean("location_valid");
        return new DefectObservationResponse(
                resultSet.getObject("event_id", UUID.class),
                resultSet.getObject("defect_id", UUID.class),
                resultSet.getObject("robot_id", UUID.class),
                resultSet.getString("robot_name"),
                locationValid,
                locationValid ? geoPoint(resultSet) : null,
                nullableDouble(resultSet, "location_accuracy_m"),
                resultSet.getString("zone_id"),
                resultSet.getString("asset_type"),
                resultSet.getString("block_type"),
                resultSet.getString("detected_defect_type"),
                resultSet.getBigDecimal("confidence"),
                resultSet.getBigDecimal("damage_ratio"),
                resultSet.getObject("image_id", UUID.class),
                instant(resultSet, "evidence_captured_at"),
                resultSet.getObject("image_width", Integer.class),
                resultSet.getObject("image_height", Integer.class),
                readBoundingBox(resultSet.getString("bbox_norm")),
                resultSet.getString("model_name"),
                resultSet.getString("model_version"),
                resultSet.getBoolean("is_representative"),
                instant(resultSet, "created_at"));
    }

    private static GeoPoint geoPoint(ResultSet resultSet) throws SQLException {
        Double longitude = nullableDouble(resultSet, "longitude");
        Double latitude = nullableDouble(resultSet, "latitude");
        if (longitude == null || latitude == null) {
            return null;
        }
        return new GeoPoint(longitude, latitude);
    }

    private Map<String, Object> readJsonObject(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(
                    json,
                    new TypeReference<Map<String, Object>>() {
                    });
        } catch (JsonProcessingException exception) {
            // 이력은 append-only이므로 값을 고칠 수 없다. 조회가 실패하지 않도록
            // 해석 불가한 snapshot은 생략한다.
            return null;
        }
    }

    private List<Double> readBoundingBox(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            List<BigDecimal> values = objectMapper.readValue(
                    json,
                    new TypeReference<List<BigDecimal>>() {
                    });
            List<Double> bbox = new ArrayList<>(values.size());
            for (BigDecimal value : values) {
                if (value == null) {
                    // bbox_norm은 4개 좌표가 모두 있어야 의미가 있다.
                    return null;
                }
                bbox.add(value.doubleValue());
            }
            return List.copyOf(bbox);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return null;
        }
    }

    private static Instant instant(ResultSet resultSet, String column)
            throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Double nullableDouble(ResultSet resultSet, String column)
            throws SQLException {
        double value = resultSet.getDouble(column);
        return resultSet.wasNull() ? null : value;
    }

    /** 결함 하나의 대표 이미지. `image_id`는 업로드가 실패한 관측이면 null이다. */
    private record ClusterImage(
            UUID defectId,
            UUID imageId
    ) {
    }

    private record ImageCandidate(
            UUID eventId,
            UUID imageId,
            Integer imageWidth,
            Integer imageHeight,
            List<Double> bboxNorm
    ) {
    }
}
