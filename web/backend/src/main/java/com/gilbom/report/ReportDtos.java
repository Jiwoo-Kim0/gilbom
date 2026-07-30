package com.gilbom.report;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class ReportDtos {

    private ReportDtos() {
    }

    public record PageResponse<T>(
            List<T> content,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {

        public PageResponse {
            content = List.copyOf(content);
        }
    }

    public record ManualReportCreateRequest(
            @NotNull
            ReportGenerationMethod generationMethod,
            @NotNull
            ReportType reportType,
            @NotBlank
            @Size(max = 200)
            String title,
            @Size(max = 255)
            String summary,
            @NotNull
            Instant periodFrom,
            @NotNull
            Instant periodTo,
            @NotBlank
            @Size(max = 100_000)
            String content,
            @NotNull
            Map<String, Object> statistics,
            Map<String, Object> filterCriteria
    ) {

        public ManualReportCreateRequest {
            statistics = immutableObject(statistics);
            filterCriteria = immutableObjectOrEmpty(
                    filterCriteria);
        }

        ReportCreateCommand toCommand() {
            if (generationMethod != ReportGenerationMethod.MANUAL
                    && generationMethod
                            != ReportGenerationMethod.TEMPLATE) {
                throw validationFailure();
            }
            return new ReportCreateCommand(
                    reportType,
                    filterCriteria,
                    new ReportContent(
                            title,
                            summary,
                            periodFrom,
                            periodTo,
                            content,
                            statistics,
                            ReportContent.emptyEvidence(),
                            null,
                            null,
                            generationMethod,
                            null));
        }
    }

    /**
     * PATCH에서 누락과 명시적 null을 구분해 summary를 지울 수 있게 한다.
     */
    public static final class ReportUpdateRequest {

        private Integer expectedVersionNo;
        private String title;
        private String summary;
        private Instant periodFrom;
        private Instant periodTo;
        private String content;
        private Map<String, Object> statistics;
        private String changeNote;
        private boolean titlePresent;
        private boolean summaryPresent;
        private boolean periodFromPresent;
        private boolean periodToPresent;
        private boolean contentPresent;
        private boolean statisticsPresent;

        @NotNull
        @Min(1)
        public Integer getExpectedVersionNo() {
            return expectedVersionNo;
        }

        @JsonSetter
        public void setExpectedVersionNo(Integer value) {
            expectedVersionNo = value;
        }

        @Size(max = 200)
        public String getTitle() {
            return title;
        }

        @JsonSetter
        public void setTitle(String value) {
            titlePresent = true;
            title = value;
        }

        @Size(max = 255)
        public String getSummary() {
            return summary;
        }

        @JsonSetter
        public void setSummary(String value) {
            summaryPresent = true;
            summary = value;
        }

        public Instant getPeriodFrom() {
            return periodFrom;
        }

        @JsonSetter
        public void setPeriodFrom(Instant value) {
            periodFromPresent = true;
            periodFrom = value;
        }

        public Instant getPeriodTo() {
            return periodTo;
        }

        @JsonSetter
        public void setPeriodTo(Instant value) {
            periodToPresent = true;
            periodTo = value;
        }

        @Size(max = 100_000)
        public String getContent() {
            return content;
        }

        @JsonSetter
        public void setContent(String value) {
            contentPresent = true;
            content = value;
        }

        public Map<String, Object> getStatistics() {
            return statistics;
        }

        @JsonSetter
        public void setStatistics(Map<String, Object> value) {
            statisticsPresent = true;
            statistics = value;
        }

        @Size(max = 500)
        public String getChangeNote() {
            return changeNote;
        }

        @JsonSetter
        public void setChangeNote(String value) {
            changeNote = value;
        }

        @AssertTrue(message = "수정할 보고서 필드가 필요합니다.")
        @JsonIgnore
        public boolean isValidChangeSet() {
            if (!(titlePresent
                    || summaryPresent
                    || periodFromPresent
                    || periodToPresent
                    || contentPresent
                    || statisticsPresent)) {
                return false;
            }
            return (!titlePresent
                        || (title != null && !title.isBlank()))
                    && (!periodFromPresent || periodFrom != null)
                    && (!periodToPresent || periodTo != null)
                    && (!contentPresent
                        || (content != null && !content.isBlank()))
                    && (!statisticsPresent || statistics != null);
        }

        ReportContent merge(ReportRecord current) {
            return new ReportContent(
                    titlePresent ? title : current.title(),
                    summaryPresent ? summary : current.summary(),
                    periodFromPresent
                            ? periodFrom
                            : current.periodFrom(),
                    periodToPresent
                            ? periodTo
                            : current.periodTo(),
                    contentPresent ? content : current.body(),
                    statisticsPresent
                            ? statistics
                            : current.statistics(),
                    current.evidenceSnapshot(),
                    null,
                    null,
                    ReportGenerationMethod.MANUAL,
                    changeNote);
        }
    }

    public record ReportListItemResponse(
            UUID reportId,
            String reportCode,
            String title,
            String summary,
            ReportType reportType,
            ReportStatus reportStatus,
            ReportGenerationMethod generationMethod,
            Instant periodFrom,
            Instant periodTo,
            int currentVersionNo,
            Integer approvedVersionNo,
            ReportCreatorType creatorType,
            UUID createdBy,
            String createdByDisplayName,
            UUID updatedBy,
            String updatedByDisplayName,
            UUID approvedBy,
            String approvedByDisplayName,
            Instant approvedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record ReportVersionSummaryResponse(
            UUID reportId,
            int versionNo,
            String title,
            String summary,
            Instant periodFrom,
            Instant periodTo,
            ReportGenerationMethod generationMethod,
            String llmModel,
            String promptVersion,
            String changeNote,
            ReportCreatorType creatorType,
            UUID createdBy,
            String createdByDisplayName,
            Instant createdAt
    ) {
    }

    public record ReportVersionResponse(
            @JsonUnwrapped
            ReportVersionSummaryResponse version,
            String content,
            Map<String, Object> statistics,
            Map<String, Object> evidenceSnapshot
    ) {

        public ReportVersionResponse {
            statistics = immutableObject(statistics);
            evidenceSnapshot = immutableObject(evidenceSnapshot);
        }
    }

    public record ApprovedPdfInfoResponse(
            int versionNo,
            String sha256,
            Instant createdAt
    ) {
    }

    public record ReportDetailResponse(
            @JsonUnwrapped
            ReportListItemResponse report,
            Map<String, Object> filterCriteria,
            ReportVersionResponse currentVersion,
            String lastRejectionReason,
            ApprovedPdfInfoResponse approvedPdf
    ) {

        public ReportDetailResponse {
            filterCriteria = immutableObjectOrEmpty(
                    filterCriteria);
        }
    }

    private static Map<String, Object> immutableObject(
            Map<String, Object> values
    ) {
        if (values == null) {
            return null;
        }
        return java.util.Collections.unmodifiableMap(
                new LinkedHashMap<>(values));
    }

    private static Map<String, Object> immutableObjectOrEmpty(
            Map<String, Object> values
    ) {
        return values == null
                ? Map.of()
                : immutableObject(values);
    }

    private static com.gilbom.common.error.BusinessException
            validationFailure() {
        return new com.gilbom.common.error.BusinessException(
                com.gilbom.common.error.ErrorCode
                        .REPORT_VALIDATION_FAILED);
    }
}
