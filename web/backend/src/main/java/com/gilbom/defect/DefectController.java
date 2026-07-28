package com.gilbom.defect;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gilbom.audit.AuditRequestContext;
import com.gilbom.auth.AuthenticatedUserIdResolver;
import com.gilbom.defect.DefectDtos.DefectDetailResponse;
import com.gilbom.defect.DefectDtos.DefectExportAcceptedResponse;
import com.gilbom.defect.DefectDtos.DefectExportRequest;
import com.gilbom.defect.DefectDtos.DefectHistoryItemResponse;
import com.gilbom.defect.DefectDtos.DefectImageUrlResponse;
import com.gilbom.defect.DefectDtos.DefectListItemResponse;
import com.gilbom.defect.DefectDtos.DefectMapResponse;
import com.gilbom.defect.DefectDtos.DefectMergeRequest;
import com.gilbom.defect.DefectDtos.DefectMergeResponse;
import com.gilbom.defect.DefectDtos.DefectObservationResponse;
import com.gilbom.defect.DefectDtos.DefectStatusUpdateRequest;
import com.gilbom.defect.DefectDtos.DefectSummaryResponse;
import com.gilbom.defect.DefectDtos.DefectUpdateRequest;
import com.gilbom.defect.DefectDtos.PageResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Swagger 21.10 Defects. 권한 코드는 25장 권장안을 그대로 사용하고, 변경 계열은
 * SecurityConfig의 전역 CSRF 규칙에 따라 X-CSRF-TOKEN을 요구한다.
 */
@Validated
@RestController
@RequestMapping("/api/v1/defects")
public class DefectController {

    private final DefectQueryService queryService;
    private final DefectCommandService commandService;
    private final AuthenticatedUserIdResolver userIdResolver;

    public DefectController(
            DefectQueryService queryService,
            DefectCommandService commandService,
            AuthenticatedUserIdResolver userIdResolver
    ) {
        this.queryService = queryService;
        this.commandService = commandService;
        this.userIdResolver = userIdResolver;
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('DEFECT_STATUS_VIEW')")
    public DefectSummaryResponse summarize(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant to,
            @RequestParam(required = false) UUID patrolAreaId,
            Authentication authentication
    ) {
        return queryService.summarize(
                userIdResolver.require(authentication),
                DefectFilter.of(
                        null,
                        from,
                        to,
                        null,
                        null,
                        null,
                        null,
                        patrolAreaId,
                        null,
                        null,
                        null,
                        null));
    }

    @GetMapping("/map")
    @PreAuthorize("hasAuthority('DEFECT_STATUS_VIEW')")
    public DefectMapResponse findMapDefects(
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant to,
            @RequestParam(required = false) List<String> workflowStatuses,
            @RequestParam(required = false) List<String> categories,
            @RequestParam(required = false) List<String> defectTypes,
            @RequestParam(required = false) List<String> severities,
            @RequestParam(required = false) UUID patrolAreaId,
            @RequestParam(required = false) UUID patrolRouteId,
            @RequestParam(required = false) UUID robotId,
            @RequestParam(required = false)
            @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal minConfidence,
            @RequestParam(required = false) @Size(max = 100) String bbox,
            Authentication authentication
    ) {
        return queryService.findMapDefects(
                userIdResolver.require(authentication),
                DefectFilter.of(
                        q,
                        from,
                        to,
                        workflowStatuses,
                        categories,
                        defectTypes,
                        severities,
                        patrolAreaId,
                        patrolRouteId,
                        robotId,
                        minConfidence,
                        bbox));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('DEFECT_STATUS_VIEW')")
    public PageResponse<DefectListItemResponse> findDefects(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 50) String sort,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant to,
            @RequestParam(required = false) List<String> workflowStatuses,
            @RequestParam(required = false) List<String> categories,
            @RequestParam(required = false) List<String> defectTypes,
            @RequestParam(required = false) List<String> severities,
            @RequestParam(required = false) UUID patrolAreaId,
            @RequestParam(required = false) UUID patrolRouteId,
            @RequestParam(required = false) UUID robotId,
            @RequestParam(required = false)
            @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal minConfidence,
            @RequestParam(required = false) @Size(max = 100) String bbox,
            Authentication authentication
    ) {
        return queryService.findDefects(
                userIdResolver.require(authentication),
                page,
                size,
                sort,
                DefectFilter.of(
                        q,
                        from,
                        to,
                        workflowStatuses,
                        categories,
                        defectTypes,
                        severities,
                        patrolAreaId,
                        patrolRouteId,
                        robotId,
                        minConfidence,
                        bbox));
    }

    @GetMapping("/{defectId}")
    @PreAuthorize("hasAuthority('DEFECT_STATUS_VIEW')")
    public DefectDetailResponse findDefect(
            @PathVariable UUID defectId,
            Authentication authentication
    ) {
        return queryService.findDefect(
                userIdResolver.require(authentication),
                defectId);
    }

    @PatchMapping("/{defectId}")
    @PreAuthorize("hasAuthority('DEFECT_STATUS_UPDATE')")
    public DefectDetailResponse updateDefect(
            @PathVariable UUID defectId,
            @Valid @RequestBody DefectUpdateRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        return commandService.updateDefect(
                userIdResolver.require(authentication),
                defectId,
                request,
                AuditRequestContext.from(servletRequest));
    }

    @PatchMapping("/{defectId}/status")
    @PreAuthorize("hasAuthority('DEFECT_STATUS_UPDATE')")
    public DefectDetailResponse changeStatus(
            @PathVariable UUID defectId,
            @Valid @RequestBody DefectStatusUpdateRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        return commandService.changeStatus(
                userIdResolver.require(authentication),
                defectId,
                request,
                AuditRequestContext.from(servletRequest));
    }

    @GetMapping("/{defectId}/observations")
    @PreAuthorize("hasAuthority('DEFECT_STATUS_VIEW')")
    public PageResponse<DefectObservationResponse> findObservations(
            @PathVariable UUID defectId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            Authentication authentication
    ) {
        return queryService.findObservations(
                userIdResolver.require(authentication),
                defectId,
                page,
                size);
    }

    @GetMapping("/{defectId}/history")
    @PreAuthorize("hasAuthority('DEFECT_STATUS_VIEW')")
    public PageResponse<DefectHistoryItemResponse> findHistory(
            @PathVariable UUID defectId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            Authentication authentication
    ) {
        return queryService.findHistory(
                userIdResolver.require(authentication),
                defectId,
                page,
                size);
    }

    @GetMapping("/{defectId}/image-url")
    @PreAuthorize("hasAuthority('DEFECT_STATUS_VIEW')")
    public DefectImageUrlResponse issueImageUrl(
            @PathVariable UUID defectId,
            @RequestParam(required = false) UUID observationId,
            Authentication authentication
    ) {
        return queryService.issueImageUrl(
                userIdResolver.require(authentication),
                defectId,
                observationId);
    }

    @PostMapping("/merge")
    @PreAuthorize("hasAuthority('DEFECT_MERGE')")
    public DefectMergeResponse merge(
            @Valid @RequestBody DefectMergeRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        return commandService.merge(
                userIdResolver.require(authentication),
                request,
                AuditRequestContext.from(servletRequest));
    }

    @PostMapping("/export")
    @PreAuthorize("hasAuthority('DEFECT_EXPORT')")
    public ResponseEntity<DefectExportAcceptedResponse> requestExport(
            @Valid @RequestBody DefectExportRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        DefectExportAcceptedResponse accepted = commandService.requestExport(
                userIdResolver.require(authentication),
                request,
                AuditRequestContext.from(servletRequest));
        // 21.10은 202(비동기 작업 접수)로 응답한다.
        return ResponseEntity.accepted().body(accepted);
    }
}
