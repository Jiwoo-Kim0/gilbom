package com.gilbom.report;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gilbom.audit.AuditRequestContext;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.report.ReportDtos.ManualReportCreateRequest;
import com.gilbom.report.ReportDtos.ReportDetailResponse;
import com.gilbom.report.ReportDtos.ReportUpdateRequest;

@Service
public class ReportCommandService {

    private final ReportAccessService accessService;
    private final ReportDomainService domainService;
    private final ReportQueryService queryService;
    private final ReportResponseMapper responseMapper;

    public ReportCommandService(
            ReportAccessService accessService,
            ReportDomainService domainService,
            ReportQueryService queryService,
            ReportResponseMapper responseMapper
    ) {
        this.accessService = accessService;
        this.domainService = domainService;
        this.queryService = queryService;
        this.responseMapper = responseMapper;
    }

    @Transactional
    public CreatedApiReport create(
            UUID userId,
            UUID idempotencyKey,
            ManualReportCreateRequest request,
        AuditRequestContext requestContext
    ) {
        accessService.require(userId, ReportAccessService.CREATE);
        if (idempotencyKey == null || request == null) {
            throw new BusinessException(
                    ErrorCode.REPORT_VALIDATION_FAILED);
        }
        CreatedReport created = domainService.create(
                request.toCommand(),
                ReportActor.user(userId),
                idempotencyKey,
                requestContext);
        // 생성 replay는 현재 DB를 다시 읽지 않고 최초 저장 snapshot을
        // 매핑해야 이후 수정과 관계없이 같은 응답을 반환한다.
        return new CreatedApiReport(
                responseMapper.detail(
                        created.report(),
                        null),
                created.created());
    }

    @Transactional
    public ReportDetailResponse update(
            UUID userId,
            UUID reportId,
            ReportUpdateRequest request,
            AuditRequestContext requestContext
    ) {
        accessService.require(userId, ReportAccessService.UPDATE);
        if (request == null) {
            throw new BusinessException(
                    ErrorCode.REPORT_VALIDATION_FAILED);
        }
        Integer expectedVersionNo =
                request.getExpectedVersionNo();
        if (expectedVersionNo == null
                || expectedVersionNo < 1
                || !request.isValidChangeSet()) {
            throw new BusinessException(
                    ErrorCode.REPORT_VALIDATION_FAILED);
        }
        ReportRecord current = domainService.findActive(reportId);
        ReportContent merged = request.merge(current);
        domainService.update(
                reportId,
                expectedVersionNo,
                merged,
                ReportActor.user(userId),
                requestContext);
        return queryService.detail(reportId);
    }

    @Transactional
    public void softDelete(
            UUID userId,
            UUID reportId,
            int expectedVersionNo,
            AuditRequestContext requestContext
    ) {
        accessService.require(userId, ReportAccessService.UPDATE);
        if (expectedVersionNo < 1) {
            throw new BusinessException(
                    ErrorCode.REPORT_VALIDATION_FAILED);
        }
        domainService.softDelete(
                reportId,
                expectedVersionNo,
                ReportActor.user(userId),
                requestContext);
    }

    public record CreatedApiReport(
            ReportDetailResponse report,
            boolean created
    ) {
    }
}
