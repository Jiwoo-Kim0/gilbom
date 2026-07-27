package com.gilbom.auth.registration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PublicRegistrationRepository {

    private final JdbcTemplate jdbcTemplate;

    public PublicRegistrationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean existsByNormalizedEmail(String normalizedEmail) {
        Boolean exists = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM auth.admin_user
                    WHERE normalized_email = ?
                )
                """,
                Boolean.class,
                normalizedEmail
        );
        return Boolean.TRUE.equals(exists);
    }

    public Optional<UUID> insertPendingAccount(
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
                    password_changed_at,
                    account_origin
                ) VALUES (
                    ?, ?, ?, ?,
                    'PENDING_APPROVAL',
                    FALSE,
                    FALSE,
                    NOW(),
                    'PUBLIC_REGISTRATION'
                )
                ON CONFLICT DO NOTHING
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

    public void appendRegistrationAudit(UUID userId) {
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
                    'USER',
                    'AUTH_PUBLIC_REGISTRATION_CREATED',
                    'USER',
                    ?,
                    jsonb_build_object(
                        'accountStatus', 'PENDING_APPROVAL',
                        'accountOrigin', 'PUBLIC_REGISTRATION',
                        'roleAssigned', FALSE
                    ),
                    'SUCCESS'
                )
                """,
                userId
        );
        if (affected != 1) {
            throw new IllegalStateException(
                    "Public registration audit was not persisted");
        }
    }
}
