package com.gilbom.auth.bootstrap;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class InitialAdminBootstrapRepository {

    private static final String BOOTSTRAP_ORIGIN = "BOOTSTRAP";

    private final JdbcTemplate jdbcTemplate;

    public InitialAdminBootstrapRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<ExistingAccount> findByNormalizedEmail(String normalizedEmail) {
        List<ExistingAccount> accounts = jdbcTemplate.query(
                """
                SELECT user_id, account_origin
                FROM auth.admin_user
                WHERE normalized_email = ?
                """,
                (resultSet, rowNumber) -> new ExistingAccount(
                        resultSet.getObject("user_id", UUID.class),
                        resultSet.getString("account_origin")
                ),
                normalizedEmail
        );
        return accounts.stream().findFirst();
    }

    public Optional<ExistingAccount> findBootstrapAccount() {
        List<ExistingAccount> accounts = jdbcTemplate.query(
                """
                SELECT user_id, account_origin
                FROM auth.admin_user
                WHERE account_origin = 'BOOTSTRAP'
                """,
                (resultSet, rowNumber) -> new ExistingAccount(
                        resultSet.getObject("user_id", UUID.class),
                        resultSet.getString("account_origin")
                )
        );
        return accounts.stream().findFirst();
    }

    public boolean hasActiveAdministrator() {
        Boolean exists = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM auth.user_role user_role
                    JOIN auth.role role USING (role_id)
                    JOIN auth.admin_user admin_user USING (user_id)
                    WHERE role.role_code = 'ADMIN'
                      AND role.is_system = TRUE
                      AND role.is_active = TRUE
                      AND user_role.is_active = TRUE
                      AND admin_user.account_status = 'ACTIVE'
                      AND admin_user.is_active = TRUE
                )
                """,
                Boolean.class
        );
        return Boolean.TRUE.equals(exists);
    }

    public Optional<UUID> insertIfEmailAbsent(
            String userCode,
            String passwordHash,
            String displayName,
            String email
    ) {
        List<UUID> insertedIds = jdbcTemplate.query(
                """
                INSERT INTO auth.admin_user (
                    user_code,
                    password_hash,
                    display_name,
                    email,
                    account_status,
                    is_active,
                    password_change_required,
                    account_origin
                ) VALUES (?, ?, ?, ?, 'ACTIVE', TRUE, TRUE, 'BOOTSTRAP')
                ON CONFLICT (normalized_email) DO NOTHING
                RETURNING user_id
                """,
                (resultSet, rowNumber) ->
                        resultSet.getObject("user_id", UUID.class),
                userCode,
                passwordHash,
                displayName,
                email
        );
        return insertedIds.stream().findFirst();
    }

    public void assignAdminRole(UUID userId) {
        int affected = jdbcTemplate.update(
                """
                INSERT INTO auth.user_role (
                    user_id,
                    role_id,
                    is_primary,
                    is_active,
                    assigned_by
                )
                SELECT ?, role_id, TRUE, TRUE, ?
                FROM auth.role
                WHERE role_code = 'ADMIN'
                  AND is_system = TRUE
                  AND is_active = TRUE
                """,
                userId,
                userId
        );
        if (affected != 1) {
            throw new IllegalStateException("Active system ADMIN role is unavailable");
        }
    }

    public void appendCreationAudit(UUID userId) {
        int affected = jdbcTemplate.update(
                """
                INSERT INTO audit.audit_log (
                    actor_type,
                    action_type,
                    target_type,
                    target_id,
                    after_value,
                    result
                ) VALUES (
                    'SYSTEM',
                    'AUTH_INITIAL_ADMIN_CREATED',
                    'USER',
                    ?,
                    jsonb_build_object(
                        'accountStatus', 'ACTIVE',
                        'accountOrigin', 'BOOTSTRAP',
                        'passwordChangeRequired', TRUE
                    ),
                    'SUCCESS'
                )
                """,
                userId
        );
        if (affected != 1) {
            throw new IllegalStateException("Initial administrator audit was not persisted");
        }
    }

    public boolean isBootstrapOrigin(ExistingAccount account) {
        return BOOTSTRAP_ORIGIN.equals(account.accountOrigin());
    }

    public record ExistingAccount(UUID userId, String accountOrigin) {
    }
}
