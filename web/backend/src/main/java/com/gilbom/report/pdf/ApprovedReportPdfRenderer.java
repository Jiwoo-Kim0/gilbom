package com.gilbom.report.pdf;

import java.time.Instant;
import java.util.UUID;

import com.gilbom.report.ReportRecord;

public interface ApprovedReportPdfRenderer {

    byte[] render(
            ReportRecord report,
            UUID approvedBy,
            Instant approvedAt);
}
