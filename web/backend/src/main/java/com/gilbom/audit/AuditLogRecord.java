package com.gilbom.audit;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record AuditLogRecord(
        AuditRequestContext requestContext,
        UUID actorUserId,
        AuditActorType actorType,
        String actionType,
        AuditTargetType targetType,
        UUID targetId,
        AuditSnapshot beforeValue,
        AuditSnapshot afterValue,
        String reason,
        AuditResult result
) {

    private static final Pattern ACTION_TYPE =
            Pattern.compile("^[A-Z][A-Z0-9_]{2,79}$");

    public AuditLogRecord {
        Objects.requireNonNull(
                requestContext,
                "requestContext must not be null"
        );
        Objects.requireNonNull(actorType, "actorType must not be null");
        Objects.requireNonNull(actionType, "actionType must not be null");
        Objects.requireNonNull(targetType, "targetType must not be null");
        Objects.requireNonNull(result, "result must not be null");
        if (!ACTION_TYPE.matcher(actionType).matches()) {
            throw new IllegalArgumentException("Invalid audit action type");
        }
        if (actorType != AuditActorType.USER && actorUserId != null) {
            throw new IllegalArgumentException(
                    "Only USER events may identify an actor user");
        }
        reason = sanitizeReason(reason);
    }

    private static String sanitizeReason(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        StringBuilder sanitized = new StringBuilder(
                Math.min(value.length(), 1000)
        );
        for (int offset = 0;
                offset < value.length() && sanitized.length() < 1000;) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint)) {
                continue;
            }
            if (sanitized.length() + Character.charCount(codePoint) > 1000) {
                break;
            }
            sanitized.appendCodePoint(codePoint);
        }
        return sanitized.isEmpty()
                ? null
                : AuditSnapshot.redactText(sanitized.toString());
    }
}
