package com.gilbom.auth.ratelimit;

import java.time.Duration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.gilbom.auth.ratelimit.RateLimitSubjectHasher.HashedRateLimitSubject;

@Repository
public class PersistentRateLimitRepository {

    private final JdbcTemplate jdbcTemplate;

    public PersistentRateLimitRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public RateLimitDecision consume(
            PublicAuthRateLimitType limitType,
            HashedRateLimitSubject subject,
            int maximumRequests,
            Duration window
    ) {
        return consume(
                limitType.databaseValue(),
                subject,
                maximumRequests,
                window
        );
    }

    public RateLimitDecision consume(
            AuthenticationRateLimitType limitType,
            HashedRateLimitSubject subject,
            int maximumRequests,
            Duration window
    ) {
        return consume(
                limitType.databaseValue(),
                subject,
                maximumRequests,
                window
        );
    }

    private RateLimitDecision consume(
            String limitType,
            HashedRateLimitSubject subject,
            int maximumRequests,
            Duration window
    ) {
        int windowSeconds = exactPositiveSeconds(window);
        if (maximumRequests <= 0) {
            throw new IllegalArgumentException(
                    "Rate-limit maximum requests must be positive");
        }

        return jdbcTemplate.queryForObject(
                """
                WITH consumed AS (
                    INSERT INTO auth.authentication_rate_limit (
                        limit_type,
                        subject_key_hash,
                        hash_key_version,
                        window_started_at,
                        last_attempt_at,
                        request_count,
                        failure_count,
                        blocked_until,
                        expires_at
                    ) VALUES (
                        ?, ?, ?,
                        statement_timestamp(),
                        statement_timestamp(),
                        1,
                        0,
                        NULL,
                        statement_timestamp() + make_interval(secs => ?)
                    )
                    ON CONFLICT (
                        limit_type,
                        hash_key_version,
                        subject_key_hash
                    ) DO UPDATE SET
                        window_started_at = CASE
                            WHEN auth.authentication_rate_limit.expires_at
                                    <= statement_timestamp()
                                THEN statement_timestamp()
                            ELSE auth.authentication_rate_limit.window_started_at
                        END,
                        last_attempt_at = statement_timestamp(),
                        request_count = CASE
                            WHEN auth.authentication_rate_limit.expires_at
                                    <= statement_timestamp()
                                THEN 1
                            ELSE auth.authentication_rate_limit.request_count + 1
                        END,
                        failure_count = CASE
                            WHEN auth.authentication_rate_limit.expires_at
                                    <= statement_timestamp()
                                THEN 0
                            ELSE auth.authentication_rate_limit.failure_count
                        END,
                        blocked_until = CASE
                            WHEN auth.authentication_rate_limit.expires_at
                                    <= statement_timestamp()
                                THEN NULL
                            WHEN auth.authentication_rate_limit.request_count + 1 > ?
                                THEN auth.authentication_rate_limit.expires_at
                            ELSE auth.authentication_rate_limit.blocked_until
                        END,
                        expires_at = CASE
                            WHEN auth.authentication_rate_limit.expires_at
                                    <= statement_timestamp()
                                THEN statement_timestamp()
                                    + make_interval(secs => ?)
                            ELSE auth.authentication_rate_limit.expires_at
                        END,
                        updated_at = statement_timestamp()
                    RETURNING request_count, blocked_until, expires_at
                )
                SELECT
                    request_count <= ?,
                    CASE
                        WHEN request_count <= ? THEN 0
                        ELSE GREATEST(
                            1,
                            CEIL(EXTRACT(
                                EPOCH FROM (
                                    COALESCE(blocked_until, expires_at)
                                    - statement_timestamp()
                                )
                            ))::BIGINT
                        )
                    END,
                    request_count
                FROM consumed
                """,
                (resultSet, rowNumber) -> {
                    boolean allowed = resultSet.getBoolean(1);
                    int requestCount = resultSet.getInt(3);
                    return new RateLimitDecision(
                            allowed,
                            resultSet.getLong(2),
                            !allowed
                                    && requestCount
                                        == maximumRequests + 1
                    );
                },
                limitType,
                subject.value(),
                subject.keyVersion(),
                windowSeconds,
                maximumRequests,
                windowSeconds,
                maximumRequests,
                maximumRequests
        );
    }

    public RateLimitDecision inspect(
            AuthenticationRateLimitType limitType,
            HashedRateLimitSubject subject
    ) {
        List<RateLimitDecision> decisions = jdbcTemplate.query(
                """
                SELECT
                    blocked_until IS NULL
                        OR blocked_until <= statement_timestamp()
                        OR expires_at <= statement_timestamp(),
                    CASE
                        WHEN blocked_until IS NULL
                            OR blocked_until <= statement_timestamp()
                            OR expires_at <= statement_timestamp()
                            THEN 0
                        ELSE GREATEST(
                            1,
                            CEIL(EXTRACT(
                                EPOCH FROM (
                                    blocked_until - statement_timestamp()
                                )
                            ))::BIGINT
                        )
                    END
                FROM auth.authentication_rate_limit
                WHERE limit_type = ?
                  AND hash_key_version = ?
                  AND subject_key_hash = ?
                """,
                (resultSet, rowNumber) -> new RateLimitDecision(
                        resultSet.getBoolean(1),
                        resultSet.getLong(2)
                ),
                limitType.databaseValue(),
                subject.keyVersion(),
                subject.value()
        );
        return decisions.stream()
                .findFirst()
                .orElseGet(() -> new RateLimitDecision(true, 0, false));
    }

    public FailureRateLimitDecision recordFailure(
            AuthenticationRateLimitType limitType,
            HashedRateLimitSubject subject,
            int maximumFailures,
            Duration failureWindow,
            Duration lockDuration
    ) {
        int failureWindowSeconds = exactPositiveSeconds(failureWindow);
        int lockSeconds = exactPositiveSeconds(lockDuration);
        if (maximumFailures <= 0) {
            throw new IllegalArgumentException(
                    "Rate-limit maximum failures must be positive");
        }

        return jdbcTemplate.queryForObject(
                """
                WITH consumed AS (
                    INSERT INTO auth.authentication_rate_limit (
                        limit_type,
                        subject_key_hash,
                        hash_key_version,
                        window_started_at,
                        last_attempt_at,
                        request_count,
                        failure_count,
                        blocked_until,
                        expires_at
                    ) VALUES (
                        ?, ?, ?,
                        statement_timestamp(),
                        statement_timestamp(),
                        1,
                        1,
                        CASE
                            WHEN 1 >= ?
                                THEN statement_timestamp()
                                    + make_interval(secs => ?)
                            ELSE NULL
                        END,
                        statement_timestamp()
                            + make_interval(secs => ?)
                    )
                    ON CONFLICT (
                        limit_type,
                        hash_key_version,
                        subject_key_hash
                    ) DO UPDATE SET
                        window_started_at = CASE
                            WHEN auth.authentication_rate_limit.expires_at
                                    <= statement_timestamp()
                                THEN statement_timestamp()
                            ELSE auth.authentication_rate_limit.window_started_at
                        END,
                        last_attempt_at = CASE
                            WHEN auth.authentication_rate_limit.blocked_until
                                    > statement_timestamp()
                                THEN auth.authentication_rate_limit.last_attempt_at
                            ELSE statement_timestamp()
                        END,
                        request_count = CASE
                            WHEN auth.authentication_rate_limit.expires_at
                                    <= statement_timestamp()
                                THEN 1
                            WHEN auth.authentication_rate_limit.blocked_until
                                    > statement_timestamp()
                                THEN auth.authentication_rate_limit.request_count
                            ELSE auth.authentication_rate_limit.request_count + 1
                        END,
                        failure_count = CASE
                            WHEN auth.authentication_rate_limit.expires_at
                                    <= statement_timestamp()
                                THEN 1
                            WHEN auth.authentication_rate_limit.blocked_until
                                    > statement_timestamp()
                                THEN auth.authentication_rate_limit.failure_count
                            ELSE auth.authentication_rate_limit.failure_count + 1
                        END,
                        blocked_until = CASE
                            WHEN auth.authentication_rate_limit.expires_at
                                    <= statement_timestamp()
                                THEN CASE
                                    WHEN 1 >= ?
                                        THEN statement_timestamp()
                                            + make_interval(secs => ?)
                                    ELSE NULL
                                END
                            WHEN auth.authentication_rate_limit.blocked_until
                                    > statement_timestamp()
                                THEN auth.authentication_rate_limit.blocked_until
                            WHEN auth.authentication_rate_limit.failure_count + 1
                                    >= ?
                                THEN statement_timestamp()
                                    + make_interval(secs => ?)
                            ELSE NULL
                        END,
                        expires_at = CASE
                            WHEN auth.authentication_rate_limit.expires_at
                                    <= statement_timestamp()
                                THEN statement_timestamp()
                                    + make_interval(secs => ?)
                            WHEN auth.authentication_rate_limit.failure_count + 1
                                    >= ?
                                THEN GREATEST(
                                    auth.authentication_rate_limit.expires_at,
                                    statement_timestamp()
                                        + make_interval(secs => ?)
                                )
                            ELSE auth.authentication_rate_limit.expires_at
                        END,
                        updated_at = statement_timestamp()
                    RETURNING
                        failure_count,
                        blocked_until,
                        expires_at
                )
                SELECT
                    failure_count,
                    blocked_until,
                    CASE
                        WHEN blocked_until IS NULL
                            OR blocked_until <= statement_timestamp()
                            THEN 0
                        ELSE GREATEST(
                            1,
                            CEIL(EXTRACT(
                                EPOCH FROM (
                                    blocked_until - statement_timestamp()
                                )
                            ))::BIGINT
                        )
                    END
                FROM consumed
                """,
                (resultSet, rowNumber) -> new FailureRateLimitDecision(
                        resultSet.getInt("failure_count"),
                        toInstant(resultSet.getTimestamp("blocked_until")),
                        resultSet.getLong(3)
                ),
                limitType.databaseValue(),
                subject.value(),
                subject.keyVersion(),
                maximumFailures,
                lockSeconds,
                failureWindowSeconds,
                maximumFailures,
                lockSeconds,
                maximumFailures,
                lockSeconds,
                failureWindowSeconds,
                maximumFailures,
                lockSeconds
        );
    }

    public int reset(
            AuthenticationRateLimitType limitType,
            HashedRateLimitSubject subject
    ) {
        return jdbcTemplate.update(
                """
                DELETE FROM auth.authentication_rate_limit
                WHERE limit_type = ?
                  AND hash_key_version = ?
                  AND subject_key_hash = ?
                """,
                limitType.databaseValue(),
                subject.keyVersion(),
                subject.value()
        );
    }

    @Transactional
    public int deleteExpiredBatch(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException(
                    "Rate-limit cleanup batch size must be positive");
        }

        return jdbcTemplate.update(
                """
                WITH expired AS (
                    SELECT rate_limit_id
                    FROM auth.authentication_rate_limit
                    WHERE expires_at < statement_timestamp()
                    ORDER BY expires_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                DELETE FROM auth.authentication_rate_limit bucket
                USING expired
                WHERE bucket.rate_limit_id = expired.rate_limit_id
                """,
                batchSize
        );
    }

    private static int exactPositiveSeconds(Duration duration) {
        if (duration == null
                || duration.isZero()
                || duration.isNegative()
                || duration.getNano() != 0
                || duration.getSeconds() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Rate-limit window must be a positive whole-second duration");
        }
        return Math.toIntExact(duration.getSeconds());
    }

    private static Instant toInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record FailureRateLimitDecision(
            int failureCount,
            Instant blockedUntil,
            long retryAfterSeconds
    ) {

        public FailureRateLimitDecision {
            if (failureCount <= 0) {
                throw new IllegalArgumentException(
                        "Failure count must be positive");
            }
            retryAfterSeconds = Math.max(0, retryAfterSeconds);
        }

        public boolean blocked() {
            return blockedUntil != null;
        }
    }
}
