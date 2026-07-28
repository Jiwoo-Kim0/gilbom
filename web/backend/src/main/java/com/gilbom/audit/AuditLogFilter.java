package com.gilbom.audit;

import java.time.Instant;
import java.util.UUID;

public record AuditLogFilter(
        int page,
        int size,
        UUID requestId,
        UUID actorUserId,
        String actionType,
        String targetType,
        UUID targetId,
        AuditResult result,
        Instant createdFrom,
        Instant createdTo
) {
}
