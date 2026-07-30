package com.gilbom.report;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

public record ReportContent(
        String title,
        String summary,
        Instant periodFrom,
        Instant periodTo,
        String body,
        Map<String, Object> statistics,
        Map<String, Object> evidenceSnapshot,
        String llmModel,
        String promptVersion,
        ReportGenerationMethod generationMethod,
        String changeNote
) {

    private static final int MAXIMUM_TITLE_LENGTH = 200;
    private static final int MAXIMUM_SUMMARY_LENGTH = 255;
    private static final int MAXIMUM_BODY_LENGTH = 100_000;
    private static final int MAXIMUM_CHANGE_NOTE_LENGTH = 500;

    public ReportContent {
        title = requiredText(title, MAXIMUM_TITLE_LENGTH, "title");
        summary = optionalText(
                summary,
                MAXIMUM_SUMMARY_LENGTH,
                "summary");
        if (periodFrom == null || periodTo == null) {
            throw validationFailure();
        }
        if (periodTo.isBefore(periodFrom)) {
            throw validationFailure();
        }
        body = requiredText(body, MAXIMUM_BODY_LENGTH, "body");
        statistics = immutableObject(statistics, "statistics");
        evidenceSnapshot = normalizedEvidence(evidenceSnapshot);
        llmModel = optionalText(llmModel, 100, "llmModel");
        promptVersion = optionalText(
                promptVersion,
                50,
                "promptVersion");
        if (generationMethod == null) {
            throw validationFailure();
        }
        if (generationMethod == ReportGenerationMethod.LLM
                && (llmModel == null || promptVersion == null)) {
            throw validationFailure();
        }
        changeNote = optionalText(
                changeNote,
                MAXIMUM_CHANGE_NOTE_LENGTH,
                "changeNote");
    }

    public static Map<String, Object> emptyEvidence() {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("defectIds", List.of());
        evidence.put("missionIds", List.of());
        evidence.put("patrolAreaIds", List.of());
        return Map.copyOf(evidence);
    }

    private static Map<String, Object> normalizedEvidence(
            Map<String, Object> value
    ) {
        Map<String, Object> source = value == null
                ? Map.of()
                : value;
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put(
                "defectIds",
                immutableList(source.get("defectIds"), "defectIds"));
        evidence.put(
                "missionIds",
                immutableList(source.get("missionIds"), "missionIds"));
        evidence.put(
                "patrolAreaIds",
                immutableList(
                        source.get("patrolAreaIds"),
                        "patrolAreaIds"));
        return Map.copyOf(evidence);
    }

    private static List<String> immutableList(
            Object value,
            String field
    ) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw validationFailure();
        }
        List<String> normalized = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof UUID uuid) {
                normalized.add(uuid.toString());
                continue;
            }
            if (item instanceof String text) {
                try {
                    normalized.add(
                            UUID.fromString(text.trim()).toString());
                    continue;
                } catch (IllegalArgumentException exception) {
                    throw validationFailure();
                }
            }
            throw validationFailure();
        }
        return List.copyOf(normalized);
    }

    private static Map<String, Object> immutableObject(
            Map<String, Object> value,
            String field
    ) {
        if (value == null) {
            throw validationFailure();
        }
        try {
            return Map.copyOf(value);
        } catch (NullPointerException exception) {
            throw validationFailure();
        }
    }

    private static String requiredText(
            String value,
            int maximumLength,
            String field
    ) {
        String normalized = optionalText(value, maximumLength, field);
        if (normalized == null) {
            throw validationFailure();
        }
        return normalized;
    }

    private static String optionalText(
            String value,
            int maximumLength,
            String field
    ) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > maximumLength) {
            throw validationFailure();
        }
        return normalized;
    }

    private static BusinessException validationFailure() {
        return new BusinessException(
                ErrorCode.REPORT_VALIDATION_FAILED);
    }
}
