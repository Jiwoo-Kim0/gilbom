package com.gilbom.report.generation;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.gilbom.report.generation.ReportLlmInput.Aggregate;
import com.gilbom.report.generation.ReportLlmInput.DefectStatusCount;
import com.gilbom.report.generation.ReportLlmInput.DefectTypeCount;
import com.gilbom.report.generation.ReportLlmInput.RiskLevel;
import com.gilbom.statistics.StatisticsDtos.DefectType;

/**
 * {@code docs/report-llm-result.schema.json}과 일치하는 정규화 결과다.
 */
public record ReportLlmResult(
        String title,
        String summary,
        Statistics statistics,
        List<KeyDefect> keyDefects,
        List<ActionRecommendation> actionRecommendations,
        Period period,
        String promptVersion,
        String modelIdentifier
) {

    public static final int MAXIMUM_ITEMS = 20;

    public ReportLlmResult {
        title = requiredText(title, 200);
        summary = requiredText(summary, 255);
        Objects.requireNonNull(
                statistics,
                "statistics must not be null");
        keyDefects = immutableList(keyDefects);
        actionRecommendations =
                immutableList(actionRecommendations);
        Objects.requireNonNull(period, "period must not be null");
        promptVersion = requiredToken(promptVersion, 50);
        modelIdentifier = requiredModel(modelIdentifier);
    }

    public record Statistics(
            Aggregate aggregate,
            List<DefectStatusCount> defectsByStatus,
            List<DefectTypeCount> defectsByType
    ) {

        public Statistics {
            Objects.requireNonNull(
                    aggregate,
                    "aggregate must not be null");
            defectsByStatus = List.copyOf(defectsByStatus);
            defectsByType = List.copyOf(defectsByType);
            if (defectsByStatus.stream().anyMatch(Objects::isNull)
                    || defectsByType.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException(
                        "statistics items must not be null");
            }
        }
    }

    public record KeyDefect(
            UUID defectId,
            String incidentCode,
            DefectType defectType,
            RiskLevel riskLevel,
            String locationLabel,
            Instant lastSeenAt,
            String rationale
    ) {

        public KeyDefect {
            Objects.requireNonNull(defectId);
            incidentCode = requiredText(incidentCode, 40);
            Objects.requireNonNull(defectType);
            Objects.requireNonNull(riskLevel);
            locationLabel = optionalText(locationLabel, 200);
            Objects.requireNonNull(lastSeenAt);
            rationale = requiredText(rationale, 500);
        }
    }

    public record ActionRecommendation(
            Priority priority,
            String recommendation,
            String rationale,
            List<UUID> relatedDefectIds
    ) {

        public ActionRecommendation {
            Objects.requireNonNull(priority);
            recommendation = requiredText(
                    recommendation,
                    500);
            rationale = requiredText(rationale, 500);
            relatedDefectIds = List.copyOf(
                    relatedDefectIds);
            if (relatedDefectIds.size() > MAXIMUM_ITEMS
                    || relatedDefectIds.stream()
                            .anyMatch(Objects::isNull)
                    || new HashSet<>(relatedDefectIds).size()
                            != relatedDefectIds.size()) {
                throw new IllegalArgumentException(
                        "related defect identifiers are invalid");
            }
        }
    }

    public record Period(Instant from, Instant to) {

        public Period {
            Objects.requireNonNull(from);
            Objects.requireNonNull(to);
            if (!from.isBefore(to)) {
                throw new IllegalArgumentException(
                        "result period is invalid");
            }
        }
    }

    public enum Priority {
        IMMEDIATE,
        HIGH,
        NORMAL,
        LOW
    }

    private static <T> List<T> immutableList(List<T> values) {
        if (values == null
                || values.size() > MAXIMUM_ITEMS
                || values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "result item collection is invalid");
        }
        return List.copyOf(values);
    }

    private static String requiredText(
            String value,
            int maximumLength
    ) {
        String normalized = optionalText(value, maximumLength);
        if (normalized == null) {
            throw new IllegalArgumentException(
                    "result text is required");
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
            throw new IllegalArgumentException(
                    "result text is invalid");
        }
        return normalized;
    }

    private static String requiredToken(
            String value,
            int maximumLength
    ) {
        String normalized = requiredText(value, maximumLength);
        if (!normalized.matches(
                "^[A-Za-z0-9][A-Za-z0-9._-]{0,49}$")) {
            throw new IllegalArgumentException(
                    "result token is invalid");
        }
        return normalized;
    }

    private static String requiredModel(String value) {
        String normalized = requiredText(value, 100);
        if (!normalized.matches(
                "^[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}$")) {
            throw new IllegalArgumentException(
                    "result model is invalid");
        }
        return normalized;
    }
}
