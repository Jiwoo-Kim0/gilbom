package com.gilbom.report;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.report.ReportDtos.PageResponse;
import com.gilbom.report.ReportDtos.ReportDetailResponse;
import com.gilbom.report.ReportDtos.ReportListItemResponse;
import com.gilbom.report.ReportDtos.ReportVersionResponse;
import com.gilbom.report.ReportDtos.ReportVersionSummaryResponse;

@Service
public class ReportQueryService {

    private final ReportAccessService accessService;
    private final ReportRepository reportRepository;
    private final ReportQueryRepository queryRepository;
    private final ReportResponseMapper responseMapper;

    public ReportQueryService(
            ReportAccessService accessService,
            ReportRepository reportRepository,
            ReportQueryRepository queryRepository,
            ReportResponseMapper responseMapper
    ) {
        this.accessService = accessService;
        this.reportRepository = reportRepository;
        this.queryRepository = queryRepository;
        this.responseMapper = responseMapper;
    }

    @Transactional(readOnly = true)
    public PageResponse<ReportListItemResponse> findReports(
            UUID userId,
            int page,
            int size,
            ReportListFilter filter
    ) {
        accessService.require(userId, ReportAccessService.VIEW);
        requirePage(page, size);
        return queryRepository.findReports(page, size, filter);
    }

    @Transactional(readOnly = true)
    public ReportDetailResponse findReport(
            UUID userId,
            UUID reportId
    ) {
        accessService.require(userId, ReportAccessService.VIEW);
        return detail(reportId);
    }

    @Transactional(readOnly = true)
    public PageResponse<ReportVersionSummaryResponse> findVersions(
            UUID userId,
            UUID reportId,
            int page,
            int size
    ) {
        accessService.require(userId, ReportAccessService.VIEW);
        requirePage(page, size);
        requireActive(reportId);
        return queryRepository.findVersions(
                reportId,
                page,
                size);
    }

    @Transactional(readOnly = true)
    public ReportVersionResponse findVersion(
            UUID userId,
            UUID reportId,
            int versionNo
    ) {
        accessService.require(userId, ReportAccessService.VIEW);
        if (versionNo < 1) {
            throw new BusinessException(
                    ErrorCode.REPORT_VALIDATION_FAILED);
        }
        requireActive(reportId);
        return queryRepository.findVersion(reportId, versionNo)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.REPORT_NOT_FOUND));
    }

    ReportDetailResponse detail(UUID reportId) {
        ReportRecord report = requireActive(reportId);
        return responseMapper.detail(
                report,
                queryRepository.findLastRejectionReason(
                        reportId));
    }

    private ReportRecord requireActive(UUID reportId) {
        if (reportId == null) {
            throw new BusinessException(
                    ErrorCode.REPORT_NOT_FOUND);
        }
        return reportRepository.findActiveById(reportId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.REPORT_NOT_FOUND));
    }

    private static void requirePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException(
                    ErrorCode.REPORT_VALIDATION_FAILED);
        }
    }
}
