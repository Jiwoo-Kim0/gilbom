package com.gilbom.report;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gilbom.audit.AuditAction;
import com.gilbom.audit.AuditLogRecord;
import com.gilbom.audit.AuditLogService;
import com.gilbom.audit.AuditRequestContext;
import com.gilbom.audit.AuditResult;
import com.gilbom.audit.AuditSnapshot;
import com.gilbom.audit.AuditTargetType;
import com.gilbom.common.concurrency.ExpectedVersionGuard;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.report.ReportCreationIdempotencyRepository.Claim;
import com.gilbom.report.pdf.ApprovedReportPdfArtifact;
import com.gilbom.report.pdf.ApprovedReportPdfService;
import com.gilbom.report.realtime.ReportRealtimeEvent;

@Service
public class ReportDomainService {

    private static final Set<String> AUDIT_FIELDS = Set.of(
            "reportCode",
            "reportType",
            "status",
            "generationMethod",
            "currentVersionNo",
            "approvedVersionNo",
            "approvedBy",
            "approvedPdfVersionNo",
            "approvedPdfSha256",
            "deleted"
    );

    private final ReportRepository repository;
    private final ReportCreationIdempotencyRepository
            idempotencyRepository;
    private final ReportStateTransitionService transitionService;
    private final ExpectedVersionGuard expectedVersionGuard;
    private final AuditLogService auditLogService;
    private final ApprovedReportPdfService approvedPdfService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public ReportDomainService(
            ReportRepository repository,
            ReportCreationIdempotencyRepository idempotencyRepository,
            ReportStateTransitionService transitionService,
            ExpectedVersionGuard expectedVersionGuard,
            AuditLogService auditLogService,
            ApprovedReportPdfService approvedPdfService,
            ApplicationEventPublisher eventPublisher,
            Clock clock
    ) {
        this.repository = repository;
        this.idempotencyRepository = idempotencyRepository;
        this.transitionService = transitionService;
        this.expectedVersionGuard = expectedVersionGuard;
        this.auditLogService = auditLogService;
        this.approvedPdfService = approvedPdfService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional
    public CreatedReport create(
            ReportCreateCommand command,
            ReportActor actor,
            UUID idempotencyKey,
            AuditRequestContext requestContext
    ) {
        return createClaimed(
                command,
                actor,
                idempotencyKey,
                requestContext,
                () -> command);
    }

    /**
     * 멱등 claim 소유자만 생성 입력 조회와 외부 생성을 실행한다.
     *
     * <p>fingerprintSource는 HTTP 요청 body의 정규화 DTO이고 commandFactory는
     * 서버 snapshot 및 LLM 결과로 최종 저장 command를 만든다.</p>
     */
    @Transactional
    public CreatedReport createClaimed(
            Object fingerprintSource,
            ReportActor actor,
            UUID idempotencyKey,
            AuditRequestContext requestContext,
            Supplier<ReportCreateCommand> commandFactory
    ) {
        Objects.requireNonNull(
                fingerprintSource,
                "fingerprintSource must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(
                idempotencyKey,
                "idempotencyKey must not be null");
        Objects.requireNonNull(
                requestContext,
                "requestContext must not be null");
        Objects.requireNonNull(
                commandFactory,
                "commandFactory must not be null");

        byte[] requestFingerprint =
                idempotencyRepository.fingerprint(fingerprintSource);
        Claim claim = idempotencyRepository.claim(
                actor,
                idempotencyKey,
                requestFingerprint);
        if (!claim.claimed()) {
            return new CreatedReport(claim.replayReport(), false);
        }

        ReportCreateCommand command = Objects.requireNonNull(
                commandFactory.get(),
                "commandFactory result must not be null");
        Instant now = clock.instant();
        UUID reportId = UUID.randomUUID();
        String reportCode = reportCode(reportId);
        repository.insertHeader(
                reportId,
                reportCode,
                command.reportType(),
                command.content(),
                command.filterCriteria(),
                actor,
                now);
        repository.insertVersion(
                reportId,
                1,
                command.content(),
                actor,
                now);

        ReportRecord created = requireReport(reportId);
        appendAudit(
                AuditAction.REPORT_CREATED,
                actor,
                requestContext,
                reportId,
                null,
                created,
                null);
        idempotencyRepository.complete(claim, created);
        publishUpdated(created);
        return new CreatedReport(created, true);
    }

    @Transactional
    public ReportRecord update(
            UUID reportId,
            int expectedVersionNo,
            ReportContent content,
            ReportActor actor,
            AuditRequestContext requestContext
    ) {
        Objects.requireNonNull(content, "content must not be null");
        ReportRecord before = lockReport(reportId);
        requireExpectedVersion(before, expectedVersionNo);
        ReportStatus targetStatus = transitionService.transition(
                before.status(),
                ReportAction.UPDATE);

        int nextVersionNo = before.currentVersionNo() + 1;
        Instant now = clock.instant();
        repository.insertVersion(
                reportId,
                nextVersionNo,
                content,
                actor,
                now);
        repository.advanceVersion(
                reportId,
                nextVersionNo,
                targetStatus,
                content,
                now);

        ReportRecord updated = requireActiveReport(reportId);
        appendAudit(
                AuditAction.REPORT_UPDATED,
                actor,
                requestContext,
                reportId,
                before,
                updated,
                content.changeNote());
        publishUpdated(updated);
        return updated;
    }

    @Transactional
    public ReportRecord submit(
            UUID reportId,
            int expectedVersionNo,
            ReportActor actor,
            AuditRequestContext requestContext
    ) {
        ReportRecord before = lockReport(reportId);
        requireExpectedVersion(before, expectedVersionNo);
        ReportStatus targetStatus = transitionService.transition(
                before.status(),
                ReportAction.SUBMIT);
        validateSubmittable(before);

        repository.changeStatus(
                reportId,
                targetStatus,
                clock.instant());
        ReportRecord submitted = requireActiveReport(reportId);
        appendAudit(
                AuditAction.REPORT_SUBMITTED,
                actor,
                requestContext,
                reportId,
                before,
                submitted,
                null);
        publishUpdated(submitted);
        return submitted;
    }

    @Transactional
    public ReportRecord approve(
            UUID reportId,
            int expectedVersionNo,
            String reason,
            ReportActor actor,
            AuditRequestContext requestContext
    ) {
        requireUserActor(actor);
        ReportRecord before = lockReport(reportId);
        requireExpectedVersion(before, expectedVersionNo);
        transitionService.transition(
                before.status(),
                ReportAction.APPROVE);
        if (actor.userId().equals(before.currentVersionCreatedBy())) {
            throw new BusinessException(
                    ErrorCode.REPORT_STATE_CONFLICT,
                    "최신 보고서 버전 작성자는 직접 승인할 수 없습니다.");
        }

        Instant approvedAt = clock.instant();
        ApprovedReportPdfArtifact pdf = approvedPdfService.create(
                before,
                actor.userId(),
                approvedAt);
        repository.approve(
                reportId,
                before.currentVersionNo(),
                actor.userId(),
                approvedAt);
        repository.bindApprovedPdf(
                reportId,
                before.currentVersionNo(),
                pdf.storageKey(),
                pdf.sha256(),
                pdf.createdAt());
        ReportRecord approved = requireActiveReport(reportId);
        appendAudit(
                AuditAction.REPORT_APPROVED,
                actor,
                requestContext,
                reportId,
                before,
                approved,
                optionalReason(reason));
        publishUpdated(approved);
        return approved;
    }

    @Transactional
    public ReportRecord reject(
            UUID reportId,
            int expectedVersionNo,
            String reason,
            ReportActor actor,
            AuditRequestContext requestContext
    ) {
        requireUserActor(actor);
        String rejectionReason = requiredReason(reason);
        ReportRecord before = lockReport(reportId);
        requireExpectedVersion(before, expectedVersionNo);
        ReportStatus targetStatus = transitionService.transition(
                before.status(),
                ReportAction.REJECT);

        repository.changeStatus(
                reportId,
                targetStatus,
                clock.instant());
        ReportRecord rejected = requireActiveReport(reportId);
        appendAudit(
                AuditAction.REPORT_REJECTED,
                actor,
                requestContext,
                reportId,
                before,
                rejected,
                rejectionReason);
        publishUpdated(rejected);
        return rejected;
    }

    @Transactional
    public void softDelete(
            UUID reportId,
            int expectedVersionNo,
            ReportActor actor,
            AuditRequestContext requestContext
    ) {
        ReportRecord before = lockReport(reportId);
        requireExpectedVersion(before, expectedVersionNo);
        transitionService.transition(
                before.status(),
                ReportAction.DELETE);

        repository.softDelete(reportId, clock.instant());
        ReportRecord deleted = requireReport(reportId);
        appendAudit(
                AuditAction.REPORT_DELETED,
                actor,
                requestContext,
                reportId,
                before,
                deleted,
                null);
        publishUpdated(deleted);
    }

    @Transactional(readOnly = true)
    public ReportRecord findActive(UUID reportId) {
        return requireActiveReport(reportId);
    }

    @Transactional(readOnly = true)
    public java.util.List<ReportRecord> findAllActive() {
        return repository.findAllActive();
    }

    private ReportRecord lockReport(UUID reportId) {
        Objects.requireNonNull(reportId, "reportId must not be null");
        return repository.findActiveByIdForUpdate(reportId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.REPORT_NOT_FOUND));
    }

    private ReportRecord requireReport(UUID reportId) {
        return repository.findById(reportId)
                .orElseThrow(() -> new IllegalStateException(
                        "Stored report was not found"));
    }

    private ReportRecord requireActiveReport(UUID reportId) {
        return repository.findActiveById(reportId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.REPORT_NOT_FOUND));
    }

    private void publishUpdated(ReportRecord report) {
        eventPublisher.publishEvent(
                ReportRealtimeEvent.from(report));
    }

    private void requireExpectedVersion(
            ReportRecord report,
            int expectedVersionNo
    ) {
        expectedVersionGuard.requireMatch(
                expectedVersionNo,
                report.currentVersionNo(),
                ErrorCode.REPORT_STATE_CONFLICT);
    }

    private static void validateSubmittable(ReportRecord report) {
        if (report.title() == null
                || report.title().isBlank()
                || report.body() == null
                || report.body().isBlank()
                || report.periodTo().isBefore(report.periodFrom())
                || report.statistics() == null
                || report.statistics().isEmpty()) {
            throw new BusinessException(
                    ErrorCode.REPORT_VALIDATION_FAILED);
        }
    }

    private static void requireUserActor(ReportActor actor) {
        if (actor == null
                || actor.creatorType() != ReportCreatorType.USER) {
            throw new BusinessException(
                    ErrorCode.REPORT_ACCESS_DENIED);
        }
    }

    private static String requiredReason(String value) {
        String reason = optionalReason(value);
        if (reason == null) {
            throw new BusinessException(
                    ErrorCode.REPORT_VALIDATION_FAILED);
        }
        return reason;
    }

    private static String optionalReason(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String reason = value.trim();
        if (reason.length() > 500) {
            throw new BusinessException(
                    ErrorCode.REPORT_VALIDATION_FAILED);
        }
        return reason;
    }

    private void appendAudit(
            AuditAction action,
            ReportActor actor,
            AuditRequestContext requestContext,
            UUID reportId,
            ReportRecord before,
            ReportRecord after,
            String reason
    ) {
        auditLogService.appendRequired(new AuditLogRecord(
                requestContext,
                actor.userId(),
                actor.auditActorType(),
                action.databaseValue(),
                AuditTargetType.REPORT,
                reportId,
                snapshot(before),
                snapshot(after),
                reason,
                AuditResult.SUCCESS));
    }

    private static AuditSnapshot snapshot(ReportRecord report) {
        if (report == null) {
            return null;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("reportCode", report.reportCode());
        values.put("reportType", report.reportType());
        values.put("status", report.status());
        values.put(
                "generationMethod",
                report.generationMethod());
        values.put(
                "currentVersionNo",
                report.currentVersionNo());
        values.put(
                "approvedVersionNo",
                report.approvedVersionNo());
        values.put("approvedBy", report.approvedBy());
        values.put(
                "approvedPdfVersionNo",
                report.approvedPdfVersionNo());
        values.put(
                "approvedPdfSha256",
                report.approvedPdfSha256() == null
                        ? null
                        : java.util.HexFormat.of().formatHex(
                                report.approvedPdfSha256()));
        values.put("deleted", report.deletedAt() != null);
        return AuditSnapshot.allow(AUDIT_FIELDS, values);
    }

    private static String reportCode(UUID reportId) {
        String compact = reportId.toString()
                .replace("-", "")
                .substring(0, 20)
                .toUpperCase(Locale.ROOT);
        return "RPT-" + compact;
    }
}
