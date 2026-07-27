package com.gilbom.auth.audit;

import java.util.Objects;
import java.util.UUID;

public record AuthenticationAuditEvent(
        AuthenticationRequestAuditContext requestContext,
        UUID actorUserId,
        ActorType actorType,
        AuthenticationAuditAction action,
        TargetType targetType,
        UUID targetId,
        Result result
) {

    public AuthenticationAuditEvent {
        Objects.requireNonNull(
                requestContext,
                "requestContext must not be null"
        );
        Objects.requireNonNull(actorType, "actorType must not be null");
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(targetType, "targetType must not be null");
        Objects.requireNonNull(result, "result must not be null");
        if (actorType != ActorType.USER && actorUserId != null) {
            throw new IllegalArgumentException(
                    "Only USER events may identify an actor user");
        }
    }

    public enum ActorType {
        USER,
        SYSTEM
    }

    public enum TargetType {
        USER,
        SESSION,
        ROLE
    }

    public enum Result {
        SUCCESS,
        FAILURE,
        DENIED
    }
}
