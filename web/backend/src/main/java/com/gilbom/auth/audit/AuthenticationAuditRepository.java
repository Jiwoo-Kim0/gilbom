package com.gilbom.auth.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuthenticationAuditRepository {

    private final JdbcTemplate jdbcTemplate;

    public AuthenticationAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void append(AuthenticationAuditEvent event) {
        AuthenticationRequestAuditContext context =
                event.requestContext();
        int affected = jdbcTemplate.update(
                """
                INSERT INTO audit.audit_log (
                    request_id,
                    actor_user_id,
                    actor_type,
                    action_type,
                    target_type,
                    target_id,
                    result,
                    ip_address,
                    user_agent
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?,
                    CAST(? AS inet),
                    ?
                )
                """,
                context.requestId(),
                event.actorUserId(),
                event.actorType().name(),
                event.action().databaseValue(),
                event.targetType().name(),
                event.targetId(),
                event.result().name(),
                context.ipAddress(),
                context.userAgent()
        );
        if (affected != 1) {
            throw new IllegalStateException(
                    "Authentication audit event was not persisted");
        }
    }
}
