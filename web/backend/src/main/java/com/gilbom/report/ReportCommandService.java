package com.gilbom.report;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gilbom.audit.AuditRequestContext;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.report.ReportDtos.LlmReportCreateRequest;
import com.gilbom.report.ReportDtos.ManualReportCreateRequest;
import com.gilbom.report.ReportDtos.ReportCreateRequest;
import com.gilbom.report.ReportDtos.ReportDecisionRequest;
import com.gilbom.report.ReportDtos.ReportDetailResponse;
import com.gilbom.report.ReportDtos.ReportRejectionRequest;
import com.gilbom.report.ReportDtos.ReportTransitionRequest;
import com.gilbom.report.ReportDtos.ReportUpdateRequest;
import com.gilbom.report.generation.ReportGenerationDataService;
import com.gilbom.report.generation.ReportGenerationDataService.PreparedCreation;
import com.gilbom.report.generation.ReportGenerationOutcome;
import com.gilbom.report.generation.ReportLlmGenerationPipeline;

@Service
public class ReportCommandService {

    private final ReportAccessService accessService;
    private final ReportDomainService domainService;
    private final ReportQueryService queryService;
    private final ReportResponseMapper responseMapper;
    private final ReportGenerationDataService generationDataService;
    private final ReportLlmGenerationPipeline generationPipeline;

    public ReportCommandService(
            ReportAccessService accessService,
            ReportDomainService domainService,
            ReportQueryService queryService,
            ReportResponseMapper responseMapper,
            ReportGenerationDataService generationDataService,
            ReportLlmGenerationPipeline generationPipeline
    ) {
        this.accessService = accessService;
        this.domainService = domainService;
        this.queryService = queryService;
        this.responseMapper = responseMapper;
        this.generationDataService = generationDataService;
        this.generationPipeline = generationPipeline;
    }

    @Transactional
    public CreatedApiReport create(
            UUID userId,
            UUID idempotencyKey,
            ReportCreateRequest request,
            AuditRequestContext requestContext
    ) {
        accessService.require(userId, ReportAccessService.CREATE);
        if (idempotencyKey == null || request == null) {
            throw new BusinessException(
                    ErrorCode.REPORT_VALIDATION_FAILED);
        }
        CreatedReport created = domainService.createClaimed(
                request,
                ReportActor.user(userId),
                idempotencyKey,
                requestContext,
                () -> createCommand(userId, request));
        // 생성 replay는 현재 DB를 다시 읽지 않고 최초 저장 snapshot을
        // 매핑해야 이후 수정과 관계없이 같은 응답을 반환한다.
        return new CreatedApiReport(
                responseMapper.detail(
                        created.report(),
                        null),
                created.created());
    }

    private ReportCreateCommand createCommand(
            UUID userId,
            ReportCreateRequest request
    ) {
        if (request instanceof ManualReportCreateRequest manual) {
            return manual.toCommand();
        }
        if (request instanceof LlmReportCreateRequest llm) {
            PreparedCreation prepared =
                    generationDataService.prepare(userId, llm);
            ReportGenerationOutcome outcome =
                    generationPipeline.generate(
                            prepared.pipelineRequest());
            return new ReportCreateCommand(
                    llm.reportType(),
                    prepared.filterCriteria(),
                    outcome.content());
        }
        throw new BusinessException(
                ErrorCode.REPORT_VALIDATION_FAILED);
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

    @Transactional
    public ReportDetailResponse submit(
            UUID userId,
            UUID reportId,
            ReportTransitionRequest request,
            AuditRequestContext requestContext
    ) {
        accessService.requireReport(
                userId,
                reportId,
                ReportAccessService.UPDATE);
        int expectedVersionNo = expectedVersionNo(request);
        domainService.submit(
                reportId,
                expectedVersionNo,
                ReportActor.user(userId),
                requestContext);
        return queryService.detail(reportId);
    }

    @Transactional
    public ReportDetailResponse approve(
            UUID userId,
            UUID reportId,
            ReportDecisionRequest request,
            AuditRequestContext requestContext
    ) {
        accessService.requireReport(
                userId,
                reportId,
                ReportAccessService.APPROVE);
        if (request == null) {
            throw new BusinessException(
                    ErrorCode.REPORT_VALIDATION_FAILED);
        }
        int expectedVersionNo = requirePositive(
                request.expectedVersionNo());
        domainService.approve(
                reportId,
                expectedVersionNo,
                request.reason(),
                ReportActor.user(userId),
                requestContext);
        return queryService.detail(reportId);
    }

    @Transactional
    public ReportDetailResponse reject(
            UUID userId,
            UUID reportId,
            ReportRejectionRequest request,
            AuditRequestContext requestContext
    ) {
        accessService.requireReport(
                userId,
                reportId,
                ReportAccessService.APPROVE);
        if (request == null
                || request.reason() == null
                || request.reason().isBlank()) {
            throw new BusinessException(
                    ErrorCode.REPORT_VALIDATION_FAILED);
        }
        int expectedVersionNo = requirePositive(
                request.expectedVersionNo());
        domainService.reject(
                reportId,
                expectedVersionNo,
                request.reason(),
                ReportActor.user(userId),
                requestContext);
        return queryService.detail(reportId);
    }

    private static int expectedVersionNo(
            ReportTransitionRequest request
    ) {
        if (request == null) {
            throw new BusinessException(
                    ErrorCode.REPORT_VALIDATION_FAILED);
        }
        return requirePositive(request.expectedVersionNo());
    }

    private static int requirePositive(Integer value) {
        if (value == null || value < 1) {
            throw new BusinessException(
                    ErrorCode.REPORT_VALIDATION_FAILED);
        }
        return value;
    }

    public record CreatedApiReport(
            ReportDetailResponse report,
            boolean created
    ) {
    }
}
