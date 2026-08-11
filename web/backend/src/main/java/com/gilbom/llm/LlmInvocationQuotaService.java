package com.gilbom.llm;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.gilbom.llm.LlmInvocationQuotaRepository.Reservation;
import com.gilbom.llm.LlmInvocationQuotaRepository.ReservationResult;

@Service
public class LlmInvocationQuotaService {

    private final LlmInvocationQuotaRepository repository;
    private final LlmCreditPolicy creditPolicy;
    private final LlmProperties properties;
    private final Clock clock;

    public LlmInvocationQuotaService(
            LlmInvocationQuotaRepository repository,
            LlmCreditPolicy creditPolicy,
            LlmProperties properties,
            Clock clock
    ) {
        this.repository = repository;
        this.creditPolicy = creditPolicy;
        this.properties = properties;
        this.clock = clock;
    }

    public ReservationResult reserve(UUID actorUserId) {
        BigDecimal maximumCredits =
                creditPolicy.maximumAttemptCredits(
                        properties.getModel(),
                        properties.getMaxOutputTokens());
        return repository.reserve(
                LocalDate.now(clock.withZone(ZoneOffset.UTC)),
                actorUserId,
                LlmPolicyGate.GMS_PROVIDER,
                properties.getModel(),
                properties.getDailyRequestLimit(),
                properties.getDailyBudget(),
                maximumCredits,
                clock.instant());
    }

    public void completeSuccess(
            Reservation reservation,
            LlmUsage usage
    ) {
        BigDecimal accounted = hasKnownUsage(usage)
                ? creditPolicy.actualCredits(
                        reservation.model(),
                        usage)
                : reservation.reservedCredits();
        repository.complete(
                reservation,
                "SUCCEEDED",
                accounted,
                hasKnownUsage(usage) ? usage : null,
                null,
                null,
                clock.instant());
    }

    public void completeFailure(
            Reservation reservation,
            LlmUsage usage,
            String safeFailureCode,
            Integer providerHttpStatus
    ) {
        // 정책상 실패와 재시도도 예산에 포함하므로 예약액을 반환하지 않는다.
        repository.complete(
                reservation,
                "FAILED",
                reservation.reservedCredits(),
                hasKnownUsage(usage) ? usage : null,
                safeFailureCode,
                providerHttpStatus,
                clock.instant());
    }

    private static boolean hasKnownUsage(LlmUsage usage) {
        return usage != null && usage.totalTokens() > 0;
    }
}
