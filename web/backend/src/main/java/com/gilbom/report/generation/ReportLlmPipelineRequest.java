package com.gilbom.report.generation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.report.ReportType;

/**
 * userId와 template metadata는 orchestration용이며 LLM prompt DTO에 포함되지 않는다.
 */
public record ReportLlmPipelineRequest(
        UUID actorUserId,
        ReportType reportType,
        String templateTitle,
        String templateSummary,
        ReportLlmInput input,
        Map<String, Object> evidenceSnapshot
) {

    public ReportLlmPipelineRequest {
        Objects.requireNonNull(
                actorUserId,
                "actorUserId must not be null");
        Objects.requireNonNull(
                reportType,
                "reportType must not be null");
        templateTitle = requiredText(templateTitle, 200);
        templateSummary = optionalText(templateSummary, 255);
        Objects.requireNonNull(input, "input must not be null");
        evidenceSnapshot = evidenceSnapshot == null
                ? defaultEvidence(input)
                : java.util.Collections.unmodifiableMap(
                        new LinkedHashMap<>(evidenceSnapshot));
    }

    private static Map<String, Object> defaultEvidence(
            ReportLlmInput input
    ) {
        return Map.of(
                "defectIds",
                input.defectEvidence().stream()
                        .map(ReportLlmInput.DefectEvidence::defectId)
                        .toList(),
                "missionIds",
                List.of(),
                "patrolAreaIds",
                List.of());
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
