package com.gilbom.report;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gilbom.audit.AuditRequestContext;
import com.gilbom.auth.AuthenticatedUserIdResolver;
import com.gilbom.report.ReportCommandService.CreatedApiReport;
import com.gilbom.report.ReportDtos.PageResponse;
import com.gilbom.report.ReportDtos.ReportCreateRequest;
import com.gilbom.report.ReportDtos.ReportDecisionRequest;
import com.gilbom.report.ReportDtos.ReportDetailResponse;
import com.gilbom.report.ReportDtos.ReportListItemResponse;
import com.gilbom.report.ReportDtos.ReportRejectionRequest;
import com.gilbom.report.ReportDtos.ReportTransitionRequest;
import com.gilbom.report.ReportDtos.ReportUpdateRequest;
import com.gilbom.report.ReportDtos.ReportVersionResponse;
import com.gilbom.report.ReportDtos.ReportVersionSummaryResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Validated
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final ReportQueryService queryService;
    private final ReportCommandService commandService;
    private final AuthenticatedUserIdResolver userIdResolver;

    public ReportController(
            ReportQueryService queryService,
            ReportCommandService commandService,
            AuthenticatedUserIdResolver userIdResolver
    ) {
        this.queryService = queryService;
        this.commandService = commandService;
        this.userIdResolver = userIdResolver;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    public PageResponse<ReportListItemResponse> findReports(
            @RequestParam(defaultValue = "0")
            @Min(0)
            int page,
            @RequestParam(defaultValue = "20")
            @Min(1)
            @Max(100)
            int size,
            @RequestParam(required = false)
            @Size(max = 100)
            @Pattern(regexp = ".*\\S.*")
            String search,
            @RequestParam(required = false)
            List<ReportStatus> statuses,
            @RequestParam(required = false)
            UUID createdBy,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant periodFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant periodTo,
            @RequestParam(required = false)
            ReportType reportType,
            @RequestParam(required = false)
            ReportGenerationMethod generationMethod,
            @RequestParam(required = false)
            @Pattern(
                    regexp =
                            "^(createdAt|updatedAt|title),(asc|desc)$")
            String sort,
            Authentication authentication
    ) {
        return queryService.findReports(
                userIdResolver.require(authentication),
                page,
                size,
                ReportListFilter.of(
                        search,
                        statuses,
                        createdBy,
                        periodFrom,
                        periodTo,
                        reportType,
                        generationMethod,
                        sort));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('REPORT_CREATE')")
    public ResponseEntity<ReportDetailResponse> createReport(
            @RequestHeader("Idempotency-Key")
            UUID idempotencyKey,
            @Valid
            @RequestBody
            ReportCreateRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        CreatedApiReport created = commandService.create(
                userIdResolver.require(authentication),
                idempotencyKey,
                request,
                AuditRequestContext.from(servletRequest));
        return ResponseEntity.created(URI.create(
                        "/api/v1/reports/"
                                + created.report().report().reportId()))
                .body(created.report());
    }

    @GetMapping("/{reportId}")
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    public ReportDetailResponse findReport(
            @PathVariable UUID reportId,
            Authentication authentication
    ) {
        return queryService.findReport(
                userIdResolver.require(authentication),
                reportId);
    }

    @PatchMapping("/{reportId}")
    @PreAuthorize("hasAuthority('REPORT_UPDATE')")
    public ReportDetailResponse updateReport(
            @PathVariable UUID reportId,
            @Valid
            @RequestBody
            ReportUpdateRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        return commandService.update(
                userIdResolver.require(authentication),
                reportId,
                request,
                AuditRequestContext.from(servletRequest));
    }

    @DeleteMapping("/{reportId}")
    @PreAuthorize("hasAuthority('REPORT_UPDATE')")
    public ResponseEntity<Void> deleteReport(
            @PathVariable UUID reportId,
            @RequestParam
            @Min(1)
            int expectedVersionNo,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        commandService.softDelete(
                userIdResolver.require(authentication),
                reportId,
                expectedVersionNo,
                AuditRequestContext.from(servletRequest));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{reportId}/submit")
    @PreAuthorize("hasAuthority('REPORT_UPDATE')")
    public ReportDetailResponse submitReport(
            @PathVariable UUID reportId,
            @Valid
            @RequestBody
            ReportTransitionRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        return commandService.submit(
                userIdResolver.require(authentication),
                reportId,
                request,
                AuditRequestContext.from(servletRequest));
    }

    @PostMapping("/{reportId}/approve")
    @PreAuthorize("hasAuthority('REPORT_APPROVE')")
    public ReportDetailResponse approveReport(
            @PathVariable UUID reportId,
            @Valid
            @RequestBody
            ReportDecisionRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        return commandService.approve(
                userIdResolver.require(authentication),
                reportId,
                request,
                AuditRequestContext.from(servletRequest));
    }

    @PostMapping("/{reportId}/reject")
    @PreAuthorize("hasAuthority('REPORT_APPROVE')")
    public ReportDetailResponse rejectReport(
            @PathVariable UUID reportId,
            @Valid
            @RequestBody
            ReportRejectionRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        return commandService.reject(
                userIdResolver.require(authentication),
                reportId,
                request,
                AuditRequestContext.from(servletRequest));
    }

    @GetMapping("/{reportId}/versions")
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    public PageResponse<ReportVersionSummaryResponse> findVersions(
            @PathVariable UUID reportId,
            @RequestParam(defaultValue = "0")
            @Min(0)
            int page,
            @RequestParam(defaultValue = "20")
            @Min(1)
            @Max(100)
            int size,
            Authentication authentication
    ) {
        return queryService.findVersions(
                userIdResolver.require(authentication),
                reportId,
                page,
                size);
    }

    @GetMapping("/{reportId}/versions/{versionNo}")
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    public ReportVersionResponse findVersion(
            @PathVariable UUID reportId,
            @PathVariable @Min(1) int versionNo,
            Authentication authentication
    ) {
        return queryService.findVersion(
                userIdResolver.require(authentication),
                reportId,
                versionNo);
    }
}
