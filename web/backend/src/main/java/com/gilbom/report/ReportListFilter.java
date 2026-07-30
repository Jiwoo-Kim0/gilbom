package com.gilbom.report;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

public record ReportListFilter(
        String search,
        Set<ReportStatus> statuses,
        UUID createdBy,
        Instant periodFrom,
        Instant periodTo,
        ReportType reportType,
        ReportGenerationMethod generationMethod,
        String orderBy
) {

    public static ReportListFilter of(
            String search,
            List<ReportStatus> statuses,
            UUID createdBy,
            Instant periodFrom,
            Instant periodTo,
            ReportType reportType,
            ReportGenerationMethod generationMethod,
            String sort
    ) {
        String normalizedSearch = normalizeSearch(search);
        if (periodFrom != null
                && periodTo != null
                && periodTo.isBefore(periodFrom)) {
            throw new BusinessException(
                    ErrorCode.REPORT_VALIDATION_FAILED);
        }
        return new ReportListFilter(
                normalizedSearch,
                statuses == null
                        ? Set.of()
                        : Set.copyOf(new LinkedHashSet<>(statuses)),
                createdBy,
                periodFrom,
                periodTo,
                reportType,
                generationMethod,
                orderBy(sort));
    }

    private static String normalizeSearch(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > 100) {
            throw new BusinessException(
                    ErrorCode.REPORT_VALIDATION_FAILED);
        }
        return normalized;
    }

    private static String orderBy(String value) {
        String normalized = value == null || value.isBlank()
                ? "createdAt,desc"
                : value.trim();
        String[] parts = normalized.split(",", -1);
        if (parts.length != 2) {
            throw validationFailure();
        }
        String column = switch (parts[0]) {
            case "createdAt" -> "report.created_at";
            case "updatedAt" -> "report.updated_at";
            case "title" -> "report.title";
            default -> throw validationFailure();
        };
        String direction = parts[1].toLowerCase(Locale.ROOT);
        if (!direction.equals("asc") && !direction.equals("desc")) {
            throw validationFailure();
        }
        return " ORDER BY "
                + column
                + " "
                + direction.toUpperCase(Locale.ROOT)
                + ", report.report_id ASC\n";
    }

    private static BusinessException validationFailure() {
        return new BusinessException(
                ErrorCode.REPORT_VALIDATION_FAILED);
    }
}
