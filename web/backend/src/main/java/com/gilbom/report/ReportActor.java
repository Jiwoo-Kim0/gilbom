package com.gilbom.report;

import java.util.Objects;
import java.util.UUID;

import com.gilbom.audit.AuditActorType;

public record ReportActor(
        ReportCreatorType creatorType,
        UUID userId,
        String principalKey
) {

    public ReportActor {
        Objects.requireNonNull(
                creatorType,
                "creatorType must not be null");
        if (principalKey == null || principalKey.isBlank()) {
            throw new IllegalArgumentException(
                    "principalKey must not be blank");
        }
        principalKey = principalKey.trim();
        if (creatorType == ReportCreatorType.USER && userId == null) {
            throw new IllegalArgumentException(
                    "USER report actor must identify a user");
        }
        if (creatorType == ReportCreatorType.USER
                && !userId.toString().equals(principalKey)) {
            throw new IllegalArgumentException(
                    "USER principalKey must match userId");
        }
        if (creatorType == ReportCreatorType.SYSTEM && userId != null) {
            throw new IllegalArgumentException(
                    "SYSTEM report actor cannot identify a user");
        }
    }

    public static ReportActor user(UUID userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        return new ReportActor(
                ReportCreatorType.USER,
                userId,
                userId.toString());
    }

    public static ReportActor system(String principalKey) {
        return new ReportActor(
                ReportCreatorType.SYSTEM,
                null,
                principalKey);
    }

    public AuditActorType auditActorType() {
        return creatorType == ReportCreatorType.USER
                ? AuditActorType.USER
                : AuditActorType.SYSTEM;
    }
}
