package com.gilbom.auth.account;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountLoginProtectionRepository {

    private final JdbcTemplate jdbcTemplate;

    public AccountLoginProtectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<AccountFailureState> recordPasswordFailure(
            UUID userId,
            int maximumFailures,
            Duration failureWindow,
            Duration lockDuration
    ) {
        int failureWindowSeconds = exactPositiveSeconds(failureWindow);
        int lockSeconds = exactPositiveSeconds(lockDuration);
        if (maximumFailures <= 0) {
            throw new IllegalArgumentException(
                    "Maximum password failures must be positive");
        }

        List<AccountFailureState> states = jdbcTemplate.query(
                """
                UPDATE auth.admin_user
                SET failed_login_count = CASE
                        WHEN last_failed_login_at IS NULL
                            OR last_failed_login_at
                                <= statement_timestamp()
                                    - make_interval(secs => ?)
                            THEN 1
                        ELSE failed_login_count + 1
                    END,
                    last_failed_login_at = statement_timestamp(),
                    locked_until = CASE
                        WHEN (
                            CASE
                                WHEN last_failed_login_at IS NULL
                                    OR last_failed_login_at
                                        <= statement_timestamp()
                                            - make_interval(secs => ?)
                                    THEN 1
                                ELSE failed_login_count + 1
                            END
                        ) >= ?
                            THEN statement_timestamp()
                                + make_interval(secs => ?)
                        ELSE NULL
                    END,
                    updated_at = statement_timestamp()
                WHERE user_id = ?
                  AND account_status = 'ACTIVE'
                  AND (
                    locked_until IS NULL
                    OR locked_until <= statement_timestamp()
                  )
                RETURNING
                    failed_login_count,
                    last_failed_login_at,
                    locked_until
                """,
                (resultSet, rowNumber) -> new AccountFailureState(
                        resultSet.getInt("failed_login_count"),
                        resultSet.getTimestamp(
                                "last_failed_login_at"
                        ).toInstant(),
                        toInstant(resultSet.getTimestamp("locked_until"))
                ),
                failureWindowSeconds,
                failureWindowSeconds,
                maximumFailures,
                lockSeconds,
                userId
        );
        return states.stream().findFirst();
    }

    public int resetPasswordFailures(UUID userId) {
        return jdbcTemplate.update(
                """
                UPDATE auth.admin_user
                SET failed_login_count = 0,
                    last_failed_login_at = NULL,
                    locked_until = NULL,
                    updated_at = statement_timestamp()
                WHERE user_id = ?
                  AND account_status = 'ACTIVE'
                  AND password_change_required = FALSE
                """,
                userId
        );
    }

    public int recordCompletedLogin(UUID userId) {
        return jdbcTemplate.update(
                """
                UPDATE auth.admin_user
                SET last_login_at = statement_timestamp(),
                    updated_at = statement_timestamp()
                WHERE user_id = ?
                  AND account_status = 'ACTIVE'
                """,
                userId
        );
    }

    private static int exactPositiveSeconds(Duration duration) {
        if (duration == null
                || duration.isZero()
                || duration.isNegative()
                || duration.getNano() != 0
                || duration.getSeconds() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Authentication duration must be positive whole seconds");
        }
        return Math.toIntExact(duration.getSeconds());
    }

    private static Instant toInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record AccountFailureState(
            int failureCount,
            Instant lastFailedLoginAt,
            Instant lockedUntil
    ) {

        public AccountFailureState {
            if (failureCount <= 0 || lastFailedLoginAt == null) {
                throw new IllegalArgumentException(
                        "Account failure state is invalid");
            }
            if (lockedUntil != null
                    && !lockedUntil.isAfter(lastFailedLoginAt)) {
                throw new IllegalArgumentException(
                        "Account lock must follow its failure");
            }
        }

        public boolean temporarilyLocked() {
            return lockedUntil != null;
        }
    }
}
