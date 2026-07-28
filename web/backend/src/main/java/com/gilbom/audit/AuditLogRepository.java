package com.gilbom.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuditLogRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AuditLogRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void append(AuditLogRecord record) {
        int affected = jdbcTemplate.update("""
                INSERT INTO audit.audit_log (
                    request_id,
                    actor_user_id,
                    actor_type,
                    action_type,
                    target_type,
                    target_id,
                    before_value,
                    after_value,
                    reason,
                    result,
                    ip_address,
                    user_agent
                ) VALUES (
                    ?, ?, ?, ?, ?, ?,
                    CAST(? AS jsonb),
                    CAST(? AS jsonb),
                    ?, ?,
                    CAST(? AS inet),
                    ?
                )
                """,
                record.requestContext().requestId(),
                record.actorUserId(),
                record.actorType().name(),
                record.actionType(),
                record.targetType().name(),
                record.targetId(),
                json(record.beforeValue()),
                json(record.afterValue()),
                record.reason(),
                record.result().name(),
                record.requestContext().ipAddress(),
                record.requestContext().userAgent());
        if (affected != 1) {
            throw new IllegalStateException(
                    "Audit event was not persisted");
        }
    }

    private String json(AuditSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(snapshot.values());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Audit snapshot could not be serialized"
            );
        }
    }
}
