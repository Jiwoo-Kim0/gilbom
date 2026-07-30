package com.gilbom.report.generation;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.statistics.StatisticsDtos.DefectType;
import com.gilbom.statistics.StatisticsDtos.DefectWorkflowStatus;

/**
 * 외부 LLM에 보낼 수 있는 보고서 전용 allowlist DTO.
 *
 * <p>사용자·로봇 식별자, 좌표, URL, 원본 로그, credential을 표현할 필드가 없다.</p>
 */
public record ReportLlmInput(
        Period period,
        Statistics statistics,
        List<DefectEvidence> defectEvidence
) {

    public static final int MAXIMUM_EVIDENCE_ITEMS = 20;
    public static final Duration MAXIMUM_PERIOD = Duration.ofDays(366);

    public ReportLlmInput {
        Objects.requireNonNull(period, "period must not be null");
        Objects.requireNonNull(
                statistics,
                "statistics must not be null");
        defectEvidence = immutableList(
                defectEvidence,
                MAXIMUM_EVIDENCE_ITEMS);
        requireUnique(
                defectEvidence.stream()
                        .map(DefectEvidence::defectId)
                        .toList());
    }

    public record Period(Instant from, Instant to) {

        public Period {
            if (from == null
                    || to == null
                    || !from.isBefore(to)
                    || Duration.between(from, to)
                            .compareTo(MAXIMUM_PERIOD) > 0) {
                throw invalid();
            }
        }
    }

    public record Statistics(
            Aggregate aggregate,
            List<DefectStatusCount> defectsByStatus,
            List<DefectTypeCount> defectsByType,
            List<DefectRiskCount> defectsByRiskLevel
    ) {

        public Statistics {
            Objects.requireNonNull(
                    aggregate,
                    "aggregate must not be null");
            defectsByStatus = immutableList(
                    defectsByStatus,
                    DefectWorkflowStatus.values().length);
            defectsByType = immutableList(
                    defectsByType,
                    DefectType.values().length);
            defectsByRiskLevel = immutableList(
                    defectsByRiskLevel,
                    RiskLevel.values().length);
            requireUnique(defectsByStatus.stream()
                    .map(DefectStatusCount::workflowStatus)
                    .toList());
            requireUnique(defectsByType.stream()
                    .map(DefectTypeCount::defectType)
                    .toList());
            requireUnique(defectsByRiskLevel.stream()
                    .map(DefectRiskCount::riskLevel)
                    .toList());
            requireDistributionTotal(
                    aggregate.defectCount(),
                    defectsByStatus.stream()
                            .mapToLong(
                                    DefectStatusCount::defectCount)
                            .toArray());
            requireDistributionTotal(
                    aggregate.defectCount(),
                    defectsByType.stream()
                            .mapToLong(
                                    DefectTypeCount::defectCount)
                            .toArray());
            requireDistributionTotal(
                    aggregate.defectCount(),
                    defectsByRiskLevel.stream()
                            .mapToLong(
                                    DefectRiskCount::defectCount)
                            .toArray());
        }
    }

    public record Aggregate(
            long missionCount,
            long completedMissionCount,
            long cancelledMissionCount,
            long failedMissionCount,
            BigDecimal completionRatePercent,
            long totalMissionDurationSeconds,
            long incidentEventCount,
            long defectCount,
            long observationCount
    ) {

        public Aggregate {
            if (missionCount < 0
                    || completedMissionCount < 0
                    || cancelledMissionCount < 0
                    || failedMissionCount < 0
                    || totalMissionDurationSeconds < 0
                    || incidentEventCount < 0
                    || defectCount < 0
                    || observationCount < 0
                    || completionRatePercent == null
                    || completionRatePercent.compareTo(
                            BigDecimal.ZERO) < 0
                    || completionRatePercent.compareTo(
                            BigDecimal.valueOf(100)) > 0
                    || completedMissionCount > missionCount
                    || cancelledMissionCount > missionCount
                    || failedMissionCount > missionCount) {
                throw invalid();
            }
        }
    }

    public record DefectStatusCount(
            DefectWorkflowStatus workflowStatus,
            long defectCount
    ) {

        public DefectStatusCount {
            Objects.requireNonNull(
                    workflowStatus,
                    "workflowStatus must not be null");
            requireNonNegative(defectCount);
        }
    }

    public record DefectTypeCount(
            DefectType defectType,
            long defectCount
    ) {

        public DefectTypeCount {
            Objects.requireNonNull(
                    defectType,
                    "defectType must not be null");
            requireNonNegative(defectCount);
        }
    }

    public record DefectRiskCount(
            RiskLevel riskLevel,
            long defectCount
    ) {

        public DefectRiskCount {
            Objects.requireNonNull(
                    riskLevel,
                    "riskLevel must not be null");
            requireNonNegative(defectCount);
        }
    }

    public record DefectEvidence(
            UUID defectId,
            String incidentCode,
            DefectType defectType,
            RiskLevel riskLevel,
            DefectWorkflowStatus workflowStatus,
            Instant lastSeenAt,
            String locationLabel,
            String minimalDescription
    ) {

        public DefectEvidence {
            Objects.requireNonNull(
                    defectId,
                    "defectId must not be null");
            incidentCode = requiredText(
                    incidentCode,
                    40);
            if (!incidentCode.matches(
                    "^INC-[A-Z0-9-]+$")) {
                throw invalid();
            }
            Objects.requireNonNull(
                    defectType,
                    "defectType must not be null");
            Objects.requireNonNull(
                    riskLevel,
                    "riskLevel must not be null");
            Objects.requireNonNull(
                    workflowStatus,
                    "workflowStatus must not be null");
            Objects.requireNonNull(
                    lastSeenAt,
                    "lastSeenAt must not be null");
            locationLabel = optionalText(
                    locationLabel,
                    200);
            minimalDescription = optionalText(
                    minimalDescription,
                    500);
        }
    }

    public enum RiskLevel {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    private static <T> List<T> immutableList(
            List<T> values,
            int maximumSize
    ) {
        if (values == null
                || values.size() > maximumSize
                || values.stream().anyMatch(Objects::isNull)) {
            throw invalid();
        }
        return List.copyOf(values);
    }

    private static void requireUnique(List<?> values) {
        Set<Object> unique = new HashSet<>();
        for (Object value : values) {
            if (!unique.add(value)) {
                throw invalid();
            }
        }
    }

    private static void requireDistributionTotal(
            long expected,
            long[] counts
    ) {
        long actual = 0;
        try {
            for (long count : counts) {
                actual = Math.addExact(actual, count);
            }
        } catch (ArithmeticException exception) {
            throw invalid();
        }
        if (actual != expected) {
            throw invalid();
        }
    }

    private static void requireNonNegative(long value) {
        if (value < 0) {
            throw invalid();
        }
    }

    private static String requiredText(
            String value,
            int maximumLength
    ) {
        String normalized = optionalText(value, maximumLength);
        if (normalized == null) {
            throw invalid();
        }
        return normalized;
    }

    private static String optionalText(
            String value,
            int maximumLength
    ) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()
                || normalized.length() > maximumLength) {
            throw invalid();
        }
        return normalized;
    }

    private static BusinessException invalid() {
        return new BusinessException(
                ErrorCode.REPORT_VALIDATION_FAILED);
    }
}
