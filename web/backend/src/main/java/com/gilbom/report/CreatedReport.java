package com.gilbom.report;

public record CreatedReport(
        ReportRecord report,
        boolean created
) {
}
