package com.gilbom.audit;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public record AuditLogItemResponse(
        long auditId,
        UUID requestId,
        UUID actorUserId,
        String actorType,
        String actionType,
        String targetType,
        UUID targetId,
        JsonNode beforeValue,
        JsonNode afterValue,
        String reason,
        String result,
        String ipAddress,
        String userAgent,
        Instant createdAt
) {
}
