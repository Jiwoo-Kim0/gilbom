package com.gilbom.report.generation;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.statistics.StatisticsDtos.DefectType;
import com.gilbom.statistics.StatisticsDtos.DefectWorkflowStatus;

/**
 * LLM 보고서 생성에서 서버 조회에 적용할 수 있는 필터 allowlist.
 */
public record ReportGenerationCriteria(
        UUID patrolAreaId,
        List<UUID> robotIds,
        List<DefectType> defectTypes,
        List<DefectWorkflowStatus> workflowStatuses
) {

    private static final int MAXIMUM_ROBOT_IDS = 100;
    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "patrolAreaId",
            "robotIds",
            "defectTypes",
            "workflowStatuses");

    public ReportGenerationCriteria {
        robotIds = unique(robotIds, MAXIMUM_ROBOT_IDS);
        defectTypes = unique(
                defectTypes,
                DefectType.values().length);
        workflowStatuses = unique(
                workflowStatuses,
                DefectWorkflowStatus.values().length);
    }

    public static ReportGenerationCriteria from(
            Map<String, Object> source
    ) {
        Map<String, Object> values = source == null
                ? Map.of()
                : source;
        if (!ALLOWED_FIELDS.containsAll(values.keySet())) {
            throw invalid();
        }
        return new ReportGenerationCriteria(
                uuid(values.get("patrolAreaId")),
                uuidList(values.get("robotIds")),
                enumList(
                        values.get("defectTypes"),
                        DefectType.class),
                enumList(
                        values.get("workflowStatuses"),
                        DefectWorkflowStatus.class));
    }

    public Map<String, Object> toSnapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (patrolAreaId != null) {
            result.put("patrolAreaId", patrolAreaId.toString());
        }
        if (!robotIds.isEmpty()) {
            result.put(
                    "robotIds",
                    robotIds.stream()
                            .map(UUID::toString)
                            .toList());
        }
        if (!defectTypes.isEmpty()) {
            result.put(
                    "defectTypes",
                    defectTypes.stream()
                            .map(Enum::name)
                            .toList());
        }
        if (!workflowStatuses.isEmpty()) {
            result.put(
                    "workflowStatuses",
                    workflowStatuses.stream()
                            .map(Enum::name)
                            .toList());
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    private static UUID uuid(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof String text) {
            try {
                return UUID.fromString(text.trim());
            } catch (IllegalArgumentException exception) {
                throw invalid();
            }
        }
        throw invalid();
    }

    private static List<UUID> uuidList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw invalid();
        }
        return list.stream()
                .map(ReportGenerationCriteria::uuid)
                .toList();
    }

    private static <E extends Enum<E>> List<E> enumList(
            Object value,
            Class<E> enumType
    ) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw invalid();
        }
        return list.stream()
                .map(item -> enumValue(item, enumType))
                .toList();
    }

    private static <E extends Enum<E>> E enumValue(
            Object value,
            Class<E> enumType
    ) {
        if (value == null) {
            throw invalid();
        }
        try {
            return Enum.valueOf(enumType, value.toString());
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static <T> List<T> unique(
            List<T> values,
            int maximumSize
    ) {
        List<T> source = values == null ? List.of() : values;
        LinkedHashSet<T> unique = new LinkedHashSet<>(source);
        if (source.size() > maximumSize
                || unique.size() != source.size()
                || unique.contains(null)) {
            throw invalid();
        }
        return List.copyOf(unique);
    }

    private static BusinessException invalid() {
        return new BusinessException(
                ErrorCode.REPORT_VALIDATION_FAILED);
    }
}
