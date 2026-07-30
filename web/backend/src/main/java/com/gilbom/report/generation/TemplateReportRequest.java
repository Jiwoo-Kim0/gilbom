package com.gilbom.report.generation;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.gilbom.report.ReportType;

public record TemplateReportRequest(
        ReportType reportType,
        String title,
        String summary,
        Instant periodFrom,
        Instant periodTo,
        Map<String, Object> statistics,
        Map<String, Object> evidenceSnapshot
) {

    public TemplateReportRequest {
        statistics = immutableObject(statistics);
        evidenceSnapshot = evidenceSnapshot == null
                ? Map.of()
                : immutableObject(evidenceSnapshot);
    }

    private static Map<String, Object> immutableObject(
            Map<String, Object> value
    ) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "Template report JSON object is required");
        }
        return java.util.Collections.unmodifiableMap(
                new LinkedHashMap<>(value));
    }
}
