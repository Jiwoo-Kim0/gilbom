package com.gilbom.report.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gilbom.llm.LlmClient;
import com.gilbom.llm.LlmConcurrencyLimiter;
import com.gilbom.llm.LlmGenerationRequest;
import com.gilbom.llm.LlmGenerationResponse;
import com.gilbom.llm.LlmInvocationQuotaRepository.DenialReason;
import com.gilbom.llm.LlmInvocationQuotaRepository.Reservation;
import com.gilbom.llm.LlmInvocationQuotaRepository.ReservationResult;
import com.gilbom.llm.LlmInvocationQuotaService;
import com.gilbom.llm.LlmProperties;
import com.gilbom.llm.LlmTransportException;
import com.gilbom.llm.LlmUsage;
import com.gilbom.report.ReportGenerationMethod;
import com.gilbom.report.ReportGenerationFallbackReason;
import com.gilbom.report.ReportType;

class ReportLlmGenerationPipelineTest {

    private static final UUID USER_ID =
            UUID.fromString("9b9b6332-3598-421e-b681-e65126aab5b4");

    private final ObjectMapper objectMapper =
            new ObjectMapper().findAndRegisterModules();

    @Test
    void returnsValidatedLlmContentWithoutFallback() {
        SequenceClient client = new SequenceClient(
                response(ReportLlmTestFixtures.validResultJson()));
        LlmInvocationQuotaService quota = allowedQuota(1);
        ReportGenerationOutcome outcome =
                pipeline(client, quota, limiter(5), noRetry())
                        .generate(request());

        assertThat(outcome.fallbackUsed()).isFalse();
        assertThat(outcome.externalAttempts()).isEqualTo(1);
        assertThat(outcome.content().generationMethod())
                .isEqualTo(ReportGenerationMethod.LLM);
        assertThat(outcome.content().title())
                .isEqualTo("7월 점자블록 결함 보고서");
        assertThat(client.requests()).hasSize(1);
        verify(quota).completeSuccess(
                any(Reservation.class),
                eq(new LlmUsage(500, 300, 800)));
    }

    @Test
    void invalidSchemaIsNotRetriedAndFallsBackToTemplate() {
        SequenceClient client = new SequenceClient(
                response("{\"title\":\"invalid\"}"));
        LlmInvocationQuotaService quota = allowedQuota(1);
        ReportGenerationOutcome outcome =
                pipeline(client, quota, limiter(5), noRetry())
                        .generate(request());

        assertThat(outcome.fallbackReason())
                .isEqualTo(
                        ReportGenerationFallbackReason
                                .LLM_RESPONSE_INVALID);
        assertThat(outcome.externalAttempts()).isEqualTo(1);
        assertThat(outcome.content().generationMethod())
                .isEqualTo(ReportGenerationMethod.TEMPLATE);
        assertThat(client.requests()).hasSize(1);
        verify(quota).completeFailure(
                any(Reservation.class),
                any(LlmUsage.class),
                eq("RESPONSE_INVALID"));
    }

    @Test
    void retriesOneTransientFailureThenSucceeds() {
        LlmTransportException failure =
                new LlmTransportException(
                        LlmTransportException.FailureKind
                                .PROVIDER_5XX);
        SequenceClient client = new SequenceClient(
                failure,
                response(ReportLlmTestFixtures.validResultJson()));
        LlmInvocationQuotaService quota = allowedQuota(2);
        LlmRetryPolicy retryPolicy = mock(LlmRetryPolicy.class);
        when(retryPolicy.shouldRetry(0, failure))
                .thenReturn(true);

        ReportGenerationOutcome outcome =
                pipeline(client, quota, limiter(5), retryPolicy)
                        .generate(request());

        assertThat(outcome.fallbackUsed()).isFalse();
        assertThat(outcome.externalAttempts()).isEqualTo(2);
        assertThat(client.requests()).hasSize(2);
        verify(retryPolicy).waitBeforeRetry(0);
        verify(quota, times(2)).reserve(USER_ID);
        verify(quota).completeFailure(
                any(Reservation.class),
                eq(null),
                eq("PROVIDER_5XX"));
    }

    @Test
    void doesNotRetryProviderFourHundredResponse() {
        SequenceClient client = new SequenceClient(
                new LlmTransportException(
                        LlmTransportException.FailureKind
                                .PROVIDER_4XX));
        LlmInvocationQuotaService quota = allowedQuota(1);

        ReportGenerationOutcome outcome =
                pipeline(client, quota, limiter(5), noRetry())
                        .generate(request());

        assertThat(outcome.fallbackReason())
                .isEqualTo(
                        ReportGenerationFallbackReason
                                .LLM_UNAVAILABLE);
        assertThat(outcome.externalAttempts()).isEqualTo(1);
        assertThat(client.requests()).hasSize(1);
    }

    @Test
    void rateAndBudgetDenialsNeverCallTheProvider() {
        SequenceClient rateClient = new SequenceClient();
        LlmInvocationQuotaService rateQuota =
                mock(LlmInvocationQuotaService.class);
        when(rateQuota.reserve(USER_ID))
                .thenReturn(new ReservationResult(
                        null,
                        DenialReason.USER_DAILY_LIMIT));

        ReportGenerationOutcome rateOutcome =
                pipeline(
                        rateClient,
                        rateQuota,
                        limiter(5),
                        noRetry())
                        .generate(request());

        assertThat(rateOutcome.fallbackReason())
                .isEqualTo(
                        ReportGenerationFallbackReason
                                .USER_DAILY_RATE_LIMIT);
        assertThat(rateClient.requests()).isEmpty();

        SequenceClient budgetClient = new SequenceClient();
        LlmInvocationQuotaService budgetQuota =
                mock(LlmInvocationQuotaService.class);
        when(budgetQuota.reserve(USER_ID))
                .thenReturn(new ReservationResult(
                        null,
                        DenialReason.DAILY_BUDGET));

        ReportGenerationOutcome budgetOutcome =
                pipeline(
                        budgetClient,
                        budgetQuota,
                        limiter(5),
                        noRetry())
                        .generate(request());

        assertThat(budgetOutcome.fallbackReason())
                .isEqualTo(
                        ReportGenerationFallbackReason
                                .DAILY_BUDGET_EXCEEDED);
        assertThat(budgetClient.requests()).isEmpty();
    }

    @Test
    void disabledPolicyAndConcurrencyLimitUseTemplateWithoutQuota() {
        SequenceClient disabled = new SequenceClient();
        disabled.available = false;
        LlmInvocationQuotaService quota =
                mock(LlmInvocationQuotaService.class);
        ReportGenerationOutcome disabledOutcome =
                pipeline(disabled, quota, limiter(1), noRetry())
                        .generate(request());

        assertThat(disabledOutcome.fallbackReason())
                .isEqualTo(
                        ReportGenerationFallbackReason
                                .POLICY_DISABLED);
        verify(quota, never()).reserve(any());

        LlmConcurrencyLimiter limiter = limiter(1);
        LlmConcurrencyLimiter.Permit held =
                limiter.tryAcquire().orElseThrow();
        try {
            SequenceClient client = new SequenceClient();
            ReportGenerationOutcome limited =
                    pipeline(client, quota, limiter, noRetry())
                            .generate(request());
            assertThat(limited.fallbackReason())
                    .isEqualTo(
                            ReportGenerationFallbackReason
                                    .CONCURRENCY_LIMIT);
            assertThat(client.requests()).isEmpty();
        } finally {
            held.close();
        }
    }

    private ReportLlmGenerationPipeline pipeline(
            LlmClient client,
            LlmInvocationQuotaService quota,
            LlmConcurrencyLimiter limiter,
            LlmRetryPolicy retryPolicy
    ) {
        LlmProperties properties = properties();
        ReportLlmInputPreparer preparer =
                new ReportLlmInputPreparer(objectMapper);
        return new ReportLlmGenerationPipeline(
                client,
                preparer,
                new ReportLlmRequestFactory(
                        Optional.empty(),
                        properties,
                        client),
                new ReportLlmResultValidator(
                        objectMapper,
                        new GeneratedReportTextGuard(
                                objectMapper)),
                new ReportLlmResultRenderer(objectMapper),
                new TemplateReportGenerator(),
                quota,
                limiter,
                retryPolicy);
    }

    private static LlmInvocationQuotaService allowedQuota(
            int reservations
    ) {
        LlmInvocationQuotaService quota =
                mock(LlmInvocationQuotaService.class);
        ReservationResult[] results =
                new ReservationResult[reservations];
        for (int index = 0;
                index < reservations;
                index++) {
            results[index] = new ReservationResult(
                    new Reservation(
                            UUID.randomUUID(),
                            "gpt-5.4-mini",
                            new BigDecimal("221.250000")),
                    null);
        }
        when(quota.reserve(USER_ID)).thenReturn(
                results[0],
                java.util.Arrays.copyOfRange(
                        results,
                        1,
                        results.length));
        return quota;
    }

    private static LlmRetryPolicy noRetry() {
        return mock(LlmRetryPolicy.class);
    }

    private static LlmConcurrencyLimiter limiter(int maximum) {
        LlmProperties properties = new LlmProperties();
        properties.setMaxConcurrentRequests(maximum);
        return new LlmConcurrencyLimiter(properties);
    }

    private static LlmProperties properties() {
        LlmProperties properties = new LlmProperties();
        properties.setImageInputEnabled(false);
        properties.setImageDeidentificationApproved(false);
        return properties;
    }

    private static ReportLlmPipelineRequest request() {
        return new ReportLlmPipelineRequest(
                USER_ID,
                ReportType.PERIODIC,
                "7월 점자블록 결함 보고서",
                "기간 내 통계를 정리한 보고서입니다.",
                ReportLlmTestFixtures.input(),
                null);
    }

    private static LlmGenerationResponse response(String content) {
        return new LlmGenerationResponse(
                content,
                "gpt-5.4-mini",
                "report-v1",
                new LlmUsage(500, 300, 800));
    }

    private static final class SequenceClient
            implements LlmClient {

        private final Deque<Object> results =
                new ArrayDeque<>();
        private final java.util.ArrayList<LlmGenerationRequest>
                requests = new java.util.ArrayList<>();
        private boolean available = true;

        private SequenceClient(Object... results) {
            this.results.addAll(List.of(results));
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public LlmGenerationResponse generate(
                LlmGenerationRequest request
        ) {
            requests.add(request);
            Object result = results.removeFirst();
            if (result instanceof RuntimeException exception) {
                throw exception;
            }
            return (LlmGenerationResponse) result;
        }

        List<LlmGenerationRequest> requests() {
            return List.copyOf(requests);
        }
    }
}
