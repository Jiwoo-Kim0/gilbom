package com.gilbom.report;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ReportRecord(
        UUID reportId,
        String reportCode,
        ReportType reportType,
        ReportStatus status,
        ReportGenerationMethod generationMethod,
        ReportCreatorType creatorType,
        int currentVersionNo,
        Integer approvedVersionNo,
        UUID createdBy,
        String createdByDisplayName,
        UUID approvedBy,
        String approvedByDisplayName,
        Instant approvedAt,
        Instant deletedAt,
        Integer approvedPdfVersionNo,
        String approvedPdfStorageKey,
        byte[] approvedPdfSha256,
        Instant approvedPdfCreatedAt,
        Map<String, Object> filterCriteria,
        Instant createdAt,
        Instant updatedAt,
        String title,
        String summary,
        Instant periodFrom,
        Instant periodTo,
        String body,
        Map<String, Object> statistics,
        Map<String, Object> evidenceSnapshot,
        UUID currentVersionCreatedBy,
        String currentVersionCreatedByDisplayName,
        ReportCreatorType currentVersionCreatorType,
        ReportGenerationMethod currentVersionGenerationMethod,
        ReportGenerationFallbackReason
                currentVersionGenerationFallbackReason,
        String llmModel,
        String promptVersion,
        String currentVersionChangeNote,
        Instant currentVersionCreatedAt
) {

    @Override
    public byte[] approvedPdfSha256() {
        return approvedPdfSha256 == null
                ? null
                : approvedPdfSha256.clone();
    }
}
