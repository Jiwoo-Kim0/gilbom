package com.gilbom.llm;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class LlmInvocationQuotaRepository {

    private final JdbcTemplate jdbcTemplate;

    public LlmInvocationQuotaRepository(
            JdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReservationResult reserve(
            LocalDate usageDate,
            UUID actorUserId,
            String provider,
            String model,
            int userDailyLimit,
            BigDecimal dailyBudget,
            BigDecimal reservedCredits,
            Instant startedAt
    ) {
        lockUsageDate(usageDate);
        Long userAttempts = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM request_control.llm_invocation_usage
                WHERE usage_date = ?
                  AND actor_user_id = ?
                """,
                Long.class,
                Date.valueOf(usageDate),
                actorUserId);
        if (userAttempts != null
                && userAttempts >= userDailyLimit) {
            return ReservationResult.denied(
                    DenialReason.USER_DAILY_LIMIT);
        }

        BigDecimal accounted = jdbcTemplate.queryForObject("""
                SELECT COALESCE(
                    SUM(accounted_credits),
                    0
                )
                FROM request_control.llm_invocation_usage
                WHERE usage_date = ?
                """,
                BigDecimal.class,
                Date.valueOf(usageDate));
        BigDecimal current = accounted == null
                ? BigDecimal.ZERO
                : accounted;
        if (current.add(reservedCredits)
                .compareTo(dailyBudget) > 0) {
            return ReservationResult.denied(
                    DenialReason.DAILY_BUDGET);
        }

        UUID attemptId = UUID.randomUUID();
        int inserted = jdbcTemplate.update("""
                INSERT INTO request_control.llm_invocation_usage (
                    attempt_id,
                    usage_date,
                    actor_user_id,
                    provider,
                    model_identifier,
                    attempt_status,
                    reserved_credits,
                    accounted_credits,
                    started_at
                ) VALUES (
                    ?, ?, ?, ?, ?,
                    'STARTED',
                    ?, ?, ?
                )
                """,
                attemptId,
                Date.valueOf(usageDate),
                actorUserId,
                provider,
                model,
                reservedCredits,
                reservedCredits,
                utc(startedAt));
        if (inserted != 1) {
            throw new IllegalStateException(
                    "LLM quota reservation was not stored");
        }
        return ReservationResult.allowed(
                new Reservation(
                        attemptId,
                        model,
                        reservedCredits));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(
            Reservation reservation,
            String status,
            BigDecimal accountedCredits,
            LlmUsage usage,
            String failureCode,
            Instant completedAt
    ) {
        int updated = jdbcTemplate.update("""
                UPDATE request_control.llm_invocation_usage
                SET attempt_status = ?,
                    accounted_credits = ?,
                    input_tokens = ?,
                    output_tokens = ?,
                    total_tokens = ?,
                    failure_code = ?,
                    completed_at = ?
                WHERE attempt_id = ?
                  AND attempt_status = 'STARTED'
                """,
                status,
                accountedCredits,
                usage == null ? null : usage.inputTokens(),
                usage == null ? null : usage.outputTokens(),
                usage == null ? null : usage.totalTokens(),
                failureCode,
                utc(completedAt),
                reservation.attemptId());
        if (updated != 1) {
            throw new IllegalStateException(
                    "LLM quota reservation was not completed");
        }
    }

    private void lockUsageDate(LocalDate usageDate) {
        jdbcTemplate.query(
                """
                SELECT pg_advisory_xact_lock(
                    hashtextextended(?, 0)
                )
                """,
                resultSet -> {
                    resultSet.next();
                    return null;
                },
                "LLM_DAILY_BUDGET|" + usageDate);
    }

    private static OffsetDateTime utc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    public record Reservation(
            UUID attemptId,
            String model,
            BigDecimal reservedCredits
    ) {
    }

    public record ReservationResult(
            Reservation reservation,
            DenialReason denialReason
    ) {

        static ReservationResult allowed(
                Reservation reservation
        ) {
            return new ReservationResult(reservation, null);
        }

        static ReservationResult denied(DenialReason reason) {
            return new ReservationResult(null, reason);
        }

        public boolean allowed() {
            return reservation != null;
        }
    }

    public enum DenialReason {
        USER_DAILY_LIMIT,
        DAILY_BUDGET
    }
}
