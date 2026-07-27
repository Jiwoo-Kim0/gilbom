package com.gilbom.auth.login;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.gilbom.auth.account.AccountAuthenticationState;
import com.gilbom.auth.account.AccountStatus;

@Repository
public class LoginAccountRepository {

    private static final String ACCOUNT_SELECT = """
            SELECT
                admin_user.user_id,
                admin_user.password_hash,
                admin_user.account_status,
                admin_user.failed_login_count,
                admin_user.last_failed_login_at,
                admin_user.locked_until,
                admin_user.password_change_required,
                EXISTS (
                    SELECT 1
                    FROM auth.mfa_factor factor
                    WHERE factor.user_id = admin_user.user_id
                      AND factor.factor_type = 'TOTP'
                      AND factor.factor_status = 'ACTIVE'
                ) AS mfa_enrolled
            FROM auth.admin_user admin_user
            """;

    private final JdbcTemplate jdbcTemplate;

    public LoginAccountRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<LoginAccount> findByNormalizedEmail(
            String normalizedEmail
    ) {
        List<LoginAccount> accounts = jdbcTemplate.query(
                ACCOUNT_SELECT + " WHERE admin_user.normalized_email = ?",
                LoginAccountRepository::mapAccount,
                normalizedEmail
        );
        return accounts.stream().findFirst();
    }

    public Optional<LoginAccount> findByUserId(UUID userId) {
        List<LoginAccount> accounts = jdbcTemplate.query(
                ACCOUNT_SELECT + " WHERE admin_user.user_id = ?",
                LoginAccountRepository::mapAccount,
                userId
        );
        return accounts.stream().findFirst();
    }

    public int updateInitialPassword(
            UUID userId,
            String expectedCurrentPasswordHash,
            String newPasswordHash
    ) {
        return jdbcTemplate.update(
                """
                UPDATE auth.admin_user
                SET password_hash = ?,
                    password_change_required = FALSE,
                    password_changed_at = statement_timestamp(),
                    failed_login_count = 0,
                    last_failed_login_at = NULL,
                    locked_until = NULL,
                    updated_at = statement_timestamp()
                WHERE user_id = ?
                  AND account_status = 'ACTIVE'
                  AND password_change_required = TRUE
                  AND password_hash = ?
                """,
                newPasswordHash,
                userId,
                expectedCurrentPasswordHash
        );
    }

    private static LoginAccount mapAccount(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        AccountAuthenticationState authenticationState =
                new AccountAuthenticationState(
                        AccountStatus.valueOf(
                                resultSet.getString("account_status")
                        ),
                        resultSet.getInt("failed_login_count"),
                        toInstant(resultSet.getTimestamp(
                                "last_failed_login_at"
                        )),
                        toInstant(resultSet.getTimestamp("locked_until"))
                );
        return new LoginAccount(
                resultSet.getObject("user_id", UUID.class),
                resultSet.getString("password_hash"),
                authenticationState,
                resultSet.getBoolean("password_change_required"),
                resultSet.getBoolean("mfa_enrolled")
        );
    }

    private static Instant toInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record LoginAccount(
            UUID userId,
            String passwordHash,
            AccountAuthenticationState authenticationState,
            boolean passwordChangeRequired,
            boolean mfaEnrolled
    ) {
    }
}
