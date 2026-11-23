package com.gilbom.defect;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.defect.DefectDtos.DefectFilterRequest;

/**
 * Swagger 21.10의 목록·지도·요약 필터를 정규화한 뒤 SQL 조건절과 bind 파라미터로
 * 바꾼다. enum 값과 정렬 칼럼은 전부 allow-list로만 통과시키므로 사용자 입력이
 * SQL 문자열에 직접 들어가지 않는다.
 */
public record DefectFilter(
        String q,
        Instant from,
        Instant to,
        Set<String> workflowStatuses,
        Set<String> categories,
        Set<String> defectTypes,
        Set<String> severities,
        UUID patrolAreaId,
        UUID patrolRouteId,
        UUID robotId,
        BigDecimal minConfidence,
        BoundingBox bbox
) {

    static final Set<String> WORKFLOW_STATUSES = Set.of(
            "NEW",
            "ACCEPTED",
            "IN_PROGRESS",
            "RESOLVED",
            "FALSE_POSITIVE",
            "REJECTED");
    static final Set<String> DEFECT_TYPES = Set.of(
            "DAMAGED",
            "MISSING",
            "INSTALLATION_INVALID",
            "UNKNOWN_ANOMALY");
    static final Set<String> RISK_LEVELS = Set.of(
            "LOW",
            "MEDIUM",
            "HIGH",
            "CRITICAL");
    static final Set<String> BLOCK_TYPES = Set.of(
            "LINEAR",
            "DOT",
            "UNKNOWN");
    /**
     * V12의 ck_defect_asset_type이 BRAILLE_BLOCK만 허용한다. Swagger의
     * categories는 자산 분류 필터이므로 asset_type에 대응시킨다.
     */
    static final Set<String> CATEGORIES = Set.of("BRAILLE_BLOCK");
    static final Set<String> RESOLUTIONS = Set.of(
            "REPAIRED",
            "CLEANED",
            "DUPLICATE",
            "REINSPECTION_REQUIRED",
            "NOT_APPLICABLE");

    /**
     * page=0, size=20, sort=field,desc 규칙에서 허용하는 정렬 칼럼이다.
     * 값은 SQL에 그대로 이어붙으므로 key만 요청에서 받는다.
     */
    private static final Map<String, String> SORTABLE_COLUMNS = Map.of(
            "lastSeenAt", "d.last_seen_at",
            "firstSeenAt", "d.first_seen_at",
            "riskLevel", "d.risk_level",
            "riskScore", "d.risk_score",
            "observationCount", "d.observation_count",
            "latestConfidence", "d.latest_confidence",
            "incidentCode", "d.incident_code",
            "createdAt", "d.created_at",
            "updatedAt", "d.updated_at");

    public record BoundingBox(
            double minLongitude,
            double minLatitude,
            double maxLongitude,
            double maxLatitude
    ) {
    }

    public static DefectFilter empty() {
        return new DefectFilter(
                null, null, null,
                Set.of(), Set.of(), Set.of(), Set.of(),
                null, null, null, null, null);
    }

    public static DefectFilter from(DefectFilterRequest request) {
        if (request == null) {
            return empty();
        }
        return of(
                request.q(),
                request.from(),
                request.to(),
                request.workflowStatuses(),
                request.categories(),
                request.defectTypes(),
                request.severities(),
                request.patrolAreaId(),
                request.patrolRouteId(),
                request.robotId(),
                request.minConfidence(),
                request.bbox());
    }

    public static DefectFilter of(
            String q,
            Instant from,
            Instant to,
            List<String> workflowStatuses,
            List<String> categories,
            List<String> defectTypes,
            List<String> severities,
            UUID patrolAreaId,
            UUID patrolRouteId,
            UUID robotId,
            BigDecimal minConfidence,
            String bbox
    ) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new BusinessException(
                    ErrorCode.COMMON_INVALID_REQUEST,
                    "조회 종료 시각이 시작 시각보다 앞설 수 없습니다.");
        }
        return new DefectFilter(
                normalizeText(q),
                from,
                to,
                normalizeEnums(workflowStatuses, WORKFLOW_STATUSES, "workflowStatuses"),
                normalizeEnums(categories, CATEGORIES, "categories"),
                normalizeEnums(defectTypes, DEFECT_TYPES, "defectTypes"),
                normalizeEnums(severities, RISK_LEVELS, "severities"),
                patrolAreaId,
                patrolRouteId,
                robotId,
                minConfidence,
                parseBoundingBox(bbox));
    }

    /**
     * 결함 테이블을 {@code d}로 alias 한 질의에 붙일 조건절을 만든다.
     * 반환 문자열은 항상 {@code AND}로 시작한다.
     */
    String toPredicate() {
        List<String> predicates = new ArrayList<>();
        if (q != null) {
            predicates.add("""
                    AND (
                        d.incident_code ILIKE CONCAT('%', :q, '%')
                        OR d.location_label ILIKE CONCAT('%', :q, '%')
                        OR d.zone_id ILIKE CONCAT('%', :q, '%')
                    )
                    """);
        }
        // from·to는 "그 기간에 관측된 결함"이므로 마지막 관측 시각을 기준으로 한다.
        if (from != null) {
            predicates.add("AND d.last_seen_at >= :from\n");
        }
        if (to != null) {
            predicates.add("AND d.last_seen_at <= :to\n");
        }
        if (!workflowStatuses.isEmpty()) {
            predicates.add("AND d.workflow_status IN (:workflowStatuses)\n");
        }
        if (!categories.isEmpty()) {
            predicates.add("AND d.asset_type IN (:categories)\n");
        }
        if (!defectTypes.isEmpty()) {
            predicates.add("AND d.defect_type IN (:defectTypes)\n");
        }
        if (!severities.isEmpty()) {
            predicates.add("AND d.risk_level IN (:severities)\n");
        }
        if (patrolAreaId != null) {
            predicates.add("AND d.patrol_area_id = :patrolAreaId\n");
        }
        if (patrolRouteId != null) {
            predicates.add("AND d.patrol_route_id = :patrolRouteId\n");
        }
        if (robotId != null) {
            predicates.add("""
                    AND EXISTS (
                        SELECT 1
                        FROM defect.defect_observation robot_observation
                        WHERE robot_observation.defect_id = d.defect_id
                          AND robot_observation.robot_id = :robotId
                    )
                    """);
        }
        if (minConfidence != null) {
            predicates.add("AND d.latest_confidence >= :minConfidence\n");
        }
        if (bbox != null) {
            // GIST 인덱스를 쓰도록 && 로 먼저 좁힌 뒤 정확한 포함 여부를 판정한다.
            predicates.add("""
                    AND d.location && ST_MakeEnvelope(
                            :minLongitude, :minLatitude,
                            :maxLongitude, :maxLatitude, 4326)
                    AND ST_Intersects(d.location, ST_MakeEnvelope(
                            :minLongitude, :minLatitude,
                            :maxLongitude, :maxLatitude, 4326))
                    """);
        }
        return String.join("", predicates);
    }

    void bind(MapSqlParameterSource parameters) {
        if (q != null) {
            parameters.addValue("q", q);
        }
        // Instant를 그대로 넘기면 드라이버가 SQL 타입을 정하지 못해 조회가 통째로
        // 실패한다. 저장소의 다른 시각 파라미터와 같이 OffsetDateTime으로 맞춘다.
        if (from != null) {
            parameters.addValue(
                    "from",
                    OffsetDateTime.ofInstant(from, ZoneOffset.UTC));
        }
        if (to != null) {
            parameters.addValue(
                    "to",
                    OffsetDateTime.ofInstant(to, ZoneOffset.UTC));
        }
        if (!workflowStatuses.isEmpty()) {
            parameters.addValue("workflowStatuses", workflowStatuses);
        }
        if (!categories.isEmpty()) {
            parameters.addValue("categories", categories);
        }
        if (!defectTypes.isEmpty()) {
            parameters.addValue("defectTypes", defectTypes);
        }
        if (!severities.isEmpty()) {
            parameters.addValue("severities", severities);
        }
        if (patrolAreaId != null) {
            parameters.addValue("patrolAreaId", patrolAreaId);
        }
        if (patrolRouteId != null) {
            parameters.addValue("patrolRouteId", patrolRouteId);
        }
        if (robotId != null) {
            parameters.addValue("robotId", robotId);
        }
        if (minConfidence != null) {
            parameters.addValue("minConfidence", minConfidence);
        }
        if (bbox != null) {
            parameters.addValue("minLongitude", bbox.minLongitude());
            parameters.addValue("minLatitude", bbox.minLatitude());
            parameters.addValue("maxLongitude", bbox.maxLongitude());
            parameters.addValue("maxLatitude", bbox.maxLatitude());
        }
    }

    /**
     * 내보내기 job에 그대로 저장할 필터 snapshot이다.
     */
    Map<String, Object> toSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        putIfPresent(snapshot, "q", q);
        putIfPresent(snapshot, "from", from == null ? null : from.toString());
        putIfPresent(snapshot, "to", to == null ? null : to.toString());
        putIfPresent(snapshot, "workflowStatuses", listOrNull(workflowStatuses));
        putIfPresent(snapshot, "categories", listOrNull(categories));
        putIfPresent(snapshot, "defectTypes", listOrNull(defectTypes));
        putIfPresent(snapshot, "severities", listOrNull(severities));
        putIfPresent(snapshot, "patrolAreaId", stringOrNull(patrolAreaId));
        putIfPresent(snapshot, "patrolRouteId", stringOrNull(patrolRouteId));
        putIfPresent(snapshot, "robotId", stringOrNull(robotId));
        putIfPresent(snapshot, "minConfidence", minConfidence);
        if (bbox != null) {
            snapshot.put("bbox", List.of(
                    bbox.minLongitude(),
                    bbox.minLatitude(),
                    bbox.maxLongitude(),
                    bbox.maxLatitude()));
        }
        return snapshot;
    }

    /**
     * {@code sort=field,desc} 를 allow-list 기반 ORDER BY 절로 바꾼다.
     * 페이지 경계가 흔들리지 않도록 항상 defect_id를 마지막 기준으로 붙인다.
     */
    static String toOrderBy(String sort) {
        if (sort == null || sort.isBlank()) {
            return "ORDER BY d.last_seen_at DESC, d.defect_id ASC\n";
        }
        String[] parts = sort.split(",");
        String column = SORTABLE_COLUMNS.get(parts[0].trim());
        if (column == null) {
            throw new BusinessException(
                    ErrorCode.COMMON_INVALID_REQUEST,
                    "지원하지 않는 정렬 기준입니다.");
        }
        String direction = "DESC";
        if (parts.length > 1) {
            String requested = parts[1].trim().toUpperCase(Locale.ROOT);
            if (!Set.of("ASC", "DESC").contains(requested)) {
                throw new BusinessException(
                        ErrorCode.COMMON_INVALID_REQUEST,
                        "정렬 방향은 asc 또는 desc여야 합니다.");
            }
            direction = requested;
        }
        return "ORDER BY " + column + " " + direction + ", d.defect_id ASC\n";
    }

    private static Set<String> normalizeEnums(
            List<String> values,
            Set<String> allowed,
            String field
    ) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            // 콤마로 이어 보내는 형태(workflowStatuses=NEW,IN_PROGRESS)도 허용한다.
            for (String token : value.split(",")) {
                String candidate = token.trim().toUpperCase(Locale.ROOT);
                if (candidate.isEmpty()) {
                    continue;
                }
                if (!allowed.contains(candidate)) {
                    throw new BusinessException(
                            ErrorCode.COMMON_INVALID_REQUEST,
                            "지원하지 않는 " + field + " 값입니다.");
                }
                normalized.add(candidate);
            }
        }
        return Set.copyOf(normalized);
    }

    private static BoundingBox parseBoundingBox(String bbox) {
        if (bbox == null || bbox.isBlank()) {
            return null;
        }
        String[] parts = bbox.split(",");
        if (parts.length != 4) {
            throw invalidBoundingBox();
        }
        double[] values = new double[4];
        for (int index = 0; index < 4; index++) {
            try {
                values[index] = Double.parseDouble(parts[index].trim());
            } catch (NumberFormatException exception) {
                throw invalidBoundingBox();
            }
        }
        double minLongitude = values[0];
        double minLatitude = values[1];
        double maxLongitude = values[2];
        double maxLatitude = values[3];
        if (minLongitude > maxLongitude || minLatitude > maxLatitude) {
            throw invalidBoundingBox();
        }
        if (minLongitude < -180 || maxLongitude > 180
                || minLatitude < -90 || maxLatitude > 90) {
            throw invalidBoundingBox();
        }
        return new BoundingBox(
                minLongitude,
                minLatitude,
                maxLongitude,
                maxLatitude);
    }

    private static BusinessException invalidBoundingBox() {
        return new BusinessException(
                ErrorCode.COMMON_INVALID_REQUEST,
                "bbox는 minLongitude,minLatitude,maxLongitude,maxLatitude 형식이어야 합니다.");
    }

    private static String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static void putIfPresent(
            Map<String, Object> target,
            String key,
            Object value
    ) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static List<String> listOrNull(Set<String> values) {
        return values.isEmpty() ? null : List.copyOf(values);
    }

    private static String stringOrNull(UUID value) {
        return value == null ? null : value.toString();
    }
}
