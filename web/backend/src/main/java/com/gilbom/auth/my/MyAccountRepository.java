package com.gilbom.auth.my;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MyAccountRepository {

    private final JdbcTemplate jdbcTemplate;

    public MyAccountRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<ProfileIdentity> findActiveProfile(UUID userId) {
        List<ProfileIdentity> profiles = jdbcTemplate.query(
                """
                SELECT
                    admin_user.user_id,
                    admin_user.email,
                    admin_user.display_name,
                    admin_user.department_name,
                    admin_user.job_title,
                    admin_user.office_name,
                    admin_user.password_changed_at,
                    admin_user.last_login_at,
                    CASE
                        WHEN EXISTS (
                            SELECT 1
                            FROM auth.mfa_factor active_factor
                            WHERE active_factor.user_id = admin_user.user_id
                              AND active_factor.factor_type = 'TOTP'
                              AND active_factor.factor_status = 'ACTIVE'
                        ) THEN 'ENROLLED'
                        WHEN EXISTS (
                            SELECT 1
                            FROM auth.mfa_factor pending_factor
                            WHERE pending_factor.user_id = admin_user.user_id
                              AND pending_factor.factor_type = 'TOTP'
                              AND pending_factor.factor_status = 'PENDING'
                        ) THEN 'ENROLLMENT_PENDING'
                        ELSE 'NOT_ENROLLED'
                    END AS mfa_status
                FROM auth.admin_user admin_user
                WHERE admin_user.user_id = ?
                  AND admin_user.account_status = 'ACTIVE'
                  AND admin_user.is_active = TRUE
                  AND admin_user.password_change_required = FALSE
                """,
                (resultSet, rowNumber) -> new ProfileIdentity(
                        resultSet.getObject("user_id", UUID.class),
                        resultSet.getString("email"),
                        resultSet.getString("display_name"),
                        resultSet.getString("department_name"),
                        resultSet.getString("job_title"),
                        resultSet.getString("office_name"),
                        MfaProfileStatus.valueOf(
                                resultSet.getString("mfa_status")
                        ),
                        toInstant(resultSet.getTimestamp(
                                "password_changed_at"
                        )),
                        toInstant(resultSet.getTimestamp("last_login_at"))
                ),
                userId
        );
        return profiles.stream().findFirst();
    }

    public Optional<PasswordAccount> findPasswordAccount(UUID userId) {
        List<PasswordAccount> accounts = jdbcTemplate.query(
                """
                SELECT admin_user.user_id, admin_user.password_hash
                FROM auth.admin_user admin_user
                WHERE admin_user.user_id = ?
                  AND admin_user.account_status = 'ACTIVE'
                  AND admin_user.is_active = TRUE
                  AND admin_user.password_change_required = FALSE
                  AND EXISTS (
                      SELECT 1
                      FROM auth.mfa_factor factor
                      WHERE factor.user_id = admin_user.user_id
                        AND factor.factor_type = 'TOTP'
                        AND factor.factor_status = 'ACTIVE'
                  )
                """,
                (resultSet, rowNumber) -> new PasswordAccount(
                        resultSet.getObject("user_id", UUID.class),
                        resultSet.getString("password_hash")
                ),
                userId
        );
        return accounts.stream().findFirst();
    }

    public int updatePassword(
            UUID userId,
            String expectedCurrentPasswordHash,
            String newPasswordHash
    ) {
        return jdbcTemplate.update(
                """
                UPDATE auth.admin_user admin_user
                SET password_hash = ?,
                    password_changed_at = statement_timestamp(),
                    failed_login_count = 0,
                    last_failed_login_at = NULL,
                    locked_until = NULL,
                    updated_at = statement_timestamp()
                WHERE admin_user.user_id = ?
                  AND admin_user.account_status = 'ACTIVE'
                  AND admin_user.is_active = TRUE
                  AND admin_user.password_change_required = FALSE
                  AND admin_user.password_hash = ?
                  AND EXISTS (
                      SELECT 1
                      FROM auth.mfa_factor factor
                      WHERE factor.user_id = admin_user.user_id
                        AND factor.factor_type = 'TOTP'
                        AND factor.factor_status = 'ACTIVE'
                  )
                """,
                newPasswordHash,
                userId,
                expectedCurrentPasswordHash
        );
    }

    private static Instant toInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record ProfileIdentity(
            UUID userId,
            String email,
            String displayName,
            String departmentName,
            String jobTitle,
            String officeName,
            MfaProfileStatus mfaStatus,
            Instant passwordChangedAt,
            Instant lastLoginAt
    ) {
    }

    public record PasswordAccount(
            UUID userId,
            String passwordHash
    ) {
    }
}
