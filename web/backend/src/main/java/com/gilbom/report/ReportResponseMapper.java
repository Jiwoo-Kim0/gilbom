package com.gilbom.report;

import java.util.HexFormat;

import org.springframework.stereotype.Component;

import com.gilbom.report.ReportDtos.ApprovedPdfInfoResponse;
import com.gilbom.report.ReportDtos.ReportDetailResponse;
import com.gilbom.report.ReportDtos.ReportListItemResponse;
import com.gilbom.report.ReportDtos.ReportVersionResponse;
import com.gilbom.report.ReportDtos.ReportVersionSummaryResponse;

@Component
public class ReportResponseMapper {

    public ReportListItemResponse listItem(ReportRecord report) {
        return new ReportListItemResponse(
                report.reportId(),
                report.reportCode(),
                report.title(),
                report.summary(),
                report.reportType(),
                report.status(),
                report.generationMethod(),
                report.periodFrom(),
                report.periodTo(),
                report.currentVersionNo(),
                report.approvedVersionNo(),
                report.creatorType(),
                report.createdBy(),
                report.createdByDisplayName(),
                report.currentVersionCreatedBy(),
                report.currentVersionCreatedByDisplayName(),
                report.approvedBy(),
                report.approvedByDisplayName(),
                report.approvedAt(),
                report.createdAt(),
                report.updatedAt());
    }

    public ReportDetailResponse detail(
            ReportRecord report,
            String lastRejectionReason
    ) {
        ReportVersionSummaryResponse summary =
                new ReportVersionSummaryResponse(
                        report.reportId(),
                        report.currentVersionNo(),
                        report.title(),
                        report.summary(),
                        report.periodFrom(),
                        report.periodTo(),
                        report.currentVersionGenerationMethod(),
                        report.currentVersionGenerationFallbackReason(),
                        report.llmModel(),
                        report.promptVersion(),
                        report.currentVersionChangeNote(),
                        report.currentVersionCreatorType(),
                        report.currentVersionCreatedBy(),
                        report.currentVersionCreatedByDisplayName(),
                        report.currentVersionCreatedAt());
        ReportVersionResponse currentVersion =
                new ReportVersionResponse(
                        summary,
                        report.body(),
                        report.statistics(),
                        report.evidenceSnapshot());
        return new ReportDetailResponse(
                listItem(report),
                report.filterCriteria(),
                currentVersion,
                lastRejectionReason,
                approvedPdf(report));
    }

    private static ApprovedPdfInfoResponse approvedPdf(
            ReportRecord report
    ) {
        if (report.approvedPdfVersionNo() == null) {
            return null;
        }
        return new ApprovedPdfInfoResponse(
                report.approvedPdfVersionNo(),
                HexFormat.of().formatHex(
                        report.approvedPdfSha256()),
                report.approvedPdfCreatedAt());
    }
}
