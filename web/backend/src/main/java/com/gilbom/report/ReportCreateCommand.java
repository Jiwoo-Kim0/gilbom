package com.gilbom.report;

import java.util.Map;
import java.util.Objects;

public record ReportCreateCommand(
        ReportType reportType,
        Map<String, Object> filterCriteria,
        ReportContent content
) {

    public ReportCreateCommand {
        Objects.requireNonNull(reportType, "reportType must not be null");
        filterCriteria = filterCriteria == null
                ? Map.of()
                : Map.copyOf(filterCriteria);
        Objects.requireNonNull(content, "content must not be null");
    }
}
