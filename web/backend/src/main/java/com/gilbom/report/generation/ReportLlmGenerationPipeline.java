package com.gilbom.report.generation;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.llm.LlmClient;
import com.gilbom.llm.LlmConcurrencyLimiter;
import com.gilbom.llm.LlmConcurrencyLimiter.Permit;
import com.gilbom.llm.LlmGenerationRequest;
import com.gilbom.llm.LlmGenerationResponse;
import com.gilbom.llm.LlmInvocationQuotaRepository.DenialReason;
import com.gilbom.llm.LlmInvocationQuotaRepository.Reservation;
import com.gilbom.llm.LlmInvocationQuotaRepository.ReservationResult;
import com.gilbom.llm.LlmInvocationQuotaService;
import com.gilbom.llm.LlmTransportException;
import com.gilbom.report.ReportContent;
import com.gilbom.report.ReportGenerationFallbackReason;
import com.gilbom.report.generation.ReportLlmInputPreparer.PreparedReportLlmInput;

/**
 * 외부 호출 전후 검증, quota, retry와 template fallback을 한 경로로 고정한다.
 */
@Service
public class ReportLlmGenerationPipeline {

    private final LlmClient llmClient;
    private final ReportLlmInputPreparer inputPreparer;
    private final ReportLlmRequestFactory requestFactory;
    private final ReportLlmResultValidator resultValidator;
    private final ReportLlmResultRenderer resultRenderer;
    private final TemplateReportGenerator templateGenerator;
    private final LlmInvocationQuotaService quotaService;
    private final LlmConcurrencyLimiter concurrencyLimiter;
    private final LlmRetryPolicy retryPolicy;

    public ReportLlmGenerationPipeline(
            LlmClient llmClient,
            ReportLlmInputPreparer inputPreparer,
            ReportLlmRequestFactory requestFactory,
            ReportLlmResultValidator resultValidator,
            ReportLlmResultRenderer resultRenderer,
            TemplateReportGenerator templateGenerator,
            LlmInvocationQuotaService quotaService,
            LlmConcurrencyLimiter concurrencyLimiter,
            LlmRetryPolicy retryPolicy
    ) {
        this.llmClient = llmClient;
        this.inputPreparer = inputPreparer;
        this.requestFactory = requestFactory;
        this.resultValidator = resultValidator;
        this.resultRenderer = resultRenderer;
        this.templateGenerator = templateGenerator;
        this.quotaService = quotaService;
        this.concurrencyLimiter = concurrencyLimiter;
        this.retryPolicy = retryPolicy;
    }

    public ReportGenerationOutcome generate(
            ReportLlmPipelineRequest request
    ) {
        PreparedReportLlmInput prepared =
                inputPreparer.prepare(request.input());
        if (!llmClient.isAvailable()) {
            return fallback(
                    request,
                    prepared,
                    ReportGenerationFallbackReason
                            .POLICY_DISABLED,
                    0);
        }

        LlmGenerationRequest llmRequest;
        try {
            llmRequest = requestFactory.create(
                    request.actorUserId(),
                    prepared);
        } catch (RuntimeException exception) {
            return fallback(
                    request,
                    prepared,
                    ReportGenerationFallbackReason
                            .IMAGE_EVIDENCE_INVALID,
                    0);
        }

        int externalAttempts = 0;
        for (int attemptIndex = 0;
                attemptIndex <= 1;
                attemptIndex++) {
            Optional<Permit> permit =
                    concurrencyLimiter.tryAcquire();
            if (permit.isEmpty()) {
                return fallback(
                        request,
                        prepared,
                        ReportGenerationFallbackReason
                                .CONCURRENCY_LIMIT,
                        externalAttempts);
            }
            try (Permit ignored = permit.get()) {
                ReservationResult reservationResult;
                try {
                    reservationResult = quotaService.reserve(
                            request.actorUserId());
                } catch (RuntimeException exception) {
                    return fallback(
                            request,
                            prepared,
                            ReportGenerationFallbackReason
                                    .QUOTA_GUARD_UNAVAILABLE,
                            externalAttempts);
                }
                if (!reservationResult.allowed()) {
                    return fallback(
                            request,
                            prepared,
                            quotaReason(reservationResult.denialReason()),
                            externalAttempts);
                }

                Reservation reservation =
                        reservationResult.reservation();
                externalAttempts++;
                LlmGenerationResponse response;
                try {
                    response = llmClient.generate(llmRequest);
                } catch (LlmTransportException exception) {
                    if (!completeFailure(
                            reservation,
                            null,
                            exception.failureKind().name())) {
                        return fallback(
                                request,
                                prepared,
                                ReportGenerationFallbackReason
                                        .QUOTA_GUARD_UNAVAILABLE,
                                externalAttempts);
                    }
                    if (retryPolicy.shouldRetry(
                            attemptIndex,
                            exception)) {
                        ignored.close();
                        retryPolicy.waitBeforeRetry(
                                attemptIndex);
                        continue;
                    }
                    return fallback(
                            request,
                            prepared,
                            ReportGenerationFallbackReason
                                    .LLM_UNAVAILABLE,
                            externalAttempts);
                } catch (BusinessException exception) {
                    String code = exception.errorCode()
                            == ErrorCode.LLM_RESPONSE_INVALID
                            ? "RESPONSE_INVALID"
                            : "CLIENT_FAILURE";
                    completeFailure(
                            reservation,
                            null,
                            code);
                    return fallback(
                            request,
                            prepared,
                            exception.errorCode()
                                    == ErrorCode.LLM_RESPONSE_INVALID
                                    ? ReportGenerationFallbackReason
                                            .LLM_RESPONSE_INVALID
                                    : ReportGenerationFallbackReason
                                            .LLM_UNAVAILABLE,
                            externalAttempts);
                } catch (RuntimeException exception) {
                    completeFailure(
                            reservation,
                            null,
                            "CLIENT_FAILURE");
                    return fallback(
                            request,
                            prepared,
                            ReportGenerationFallbackReason
                                    .LLM_UNAVAILABLE,
                            externalAttempts);
                }

                ReportLlmResult result;
                try {
                    result = resultValidator.validate(
                            response,
                            prepared);
                } catch (BusinessException exception) {
                    completeFailure(
                            reservation,
                            response.usage(),
                            "RESPONSE_INVALID");
                    return fallback(
                            request,
                            prepared,
                            ReportGenerationFallbackReason
                                    .LLM_RESPONSE_INVALID,
                            externalAttempts);
                }
                try {
                    quotaService.completeSuccess(
                            reservation,
                            response.usage());
                } catch (RuntimeException exception) {
                    return fallback(
                            request,
                            prepared,
                            ReportGenerationFallbackReason
                                    .QUOTA_GUARD_UNAVAILABLE,
                            externalAttempts);
                }
                ReportContent content = resultRenderer.render(
                        result,
                        prepared,
                        request.evidenceSnapshot());
                return new ReportGenerationOutcome(
                        content,
                        null,
                        externalAttempts);
            }
        }
        return fallback(
                request,
                prepared,
                ReportGenerationFallbackReason.LLM_UNAVAILABLE,
                externalAttempts);
    }

    private boolean completeFailure(
            Reservation reservation,
            com.gilbom.llm.LlmUsage usage,
            String safeCode
    ) {
        try {
            quotaService.completeFailure(
                    reservation,
                    usage,
                    safeCode);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private ReportGenerationOutcome fallback(
            ReportLlmPipelineRequest request,
            PreparedReportLlmInput prepared,
            ReportGenerationFallbackReason reason,
            int externalAttempts
    ) {
        ReportContent content = templateGenerator.generate(
                new TemplateReportRequest(
                        request.reportType(),
                        request.templateTitle(),
                        request.templateSummary(),
                        prepared.source().period().from(),
                        prepared.source().period().to(),
                        prepared.promptInput()
                                .get("statistics")
                                instanceof java.util.Map<?, ?> map
                                ? stringObjectMap(map)
                                : java.util.Map.of(),
                        request.evidenceSnapshot()))
                .withGenerationFallbackReason(reason);
        return new ReportGenerationOutcome(
                content,
                reason,
                externalAttempts);
    }

    private static ReportGenerationFallbackReason quotaReason(
            DenialReason reason
    ) {
        return reason == DenialReason.USER_DAILY_LIMIT
                ? ReportGenerationFallbackReason
                        .USER_DAILY_RATE_LIMIT
                : ReportGenerationFallbackReason
                        .DAILY_BUDGET_EXCEEDED;
    }

    private static java.util.Map<String, Object> stringObjectMap(
            java.util.Map<?, ?> source
    ) {
        java.util.Map<String, Object> result =
                new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<?, ?> entry
                : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalStateException(
                        "Prepared statistics key is invalid");
            }
            result.put(key, entry.getValue());
        }
        return java.util.Collections.unmodifiableMap(result);
    }
}
