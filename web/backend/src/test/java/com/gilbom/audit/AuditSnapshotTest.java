package com.gilbom.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class AuditSnapshotTest {

    @Test
    void appliesAllowlistAndRecursivelyRedactsSensitiveValues() {
        AuditSnapshot snapshot = AuditSnapshot.allow(
                Set.of(
                        "status",
                        "password",
                        "metadata",
                        "entries",
                        "imageBase64"
                ),
                Map.of(
                        "status", "ACTIVE",
                        "email", "must-not-be-copied@example.test",
                        "password", "plain-password",
                        "metadata", Map.of(
                                "csrf_token", "csrf-value",
                                "note", "token=embedded-value"
                        ),
                        "entries", List.of(Map.of(
                                "sessionId", "session-value",
                                "roleId", "ROLE-1"
                        )),
                        "imageBase64", "sensitive-image-data"
                )
        );

        assertThat(snapshot.values())
                .containsEntry("status", "ACTIVE")
                .doesNotContainKey("email")
                .containsEntry("password", AuditSnapshot.REDACTED)
                .containsEntry(
                        "imageBase64",
                        AuditSnapshot.REDACTED);
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata =
                (Map<String, Object>) snapshot.values().get("metadata");
        assertThat(metadata)
                .containsEntry("csrf_token", AuditSnapshot.REDACTED)
                .containsEntry("note", "token=[REDACTED]");
        @SuppressWarnings("unchecked")
        Map<String, Object> firstEntry =
                (Map<String, Object>) ((List<?>) snapshot.values()
                        .get("entries")).getFirst();
        assertThat(firstEntry)
                .containsEntry("sessionId", AuditSnapshot.REDACTED)
                .containsEntry("roleId", "ROLE-1");
    }

    @Test
    void sanitizesSecretsEmbeddedInReasonText() {
        AuditLogRecord record = new AuditLogRecord(
                AuditRequestContext.system(),
                null,
                AuditActorType.SYSTEM,
                "ADMIN_SETTING_CHANGED",
                AuditTargetType.SETTING,
                null,
                null,
                null,
                "rotation: password=plain Bearer jwt-value "
                        + "presigned_url=https://sensitive "
                        + "image_base64=sensitive-image",
                AuditResult.SUCCESS
        );

        assertThat(record.reason())
                .contains("password=[REDACTED]")
                .contains("Bearer [REDACTED]")
                .doesNotContain("plain")
                .doesNotContain("jwt-value")
                .doesNotContain("https://sensitive")
                .doesNotContain("sensitive-image");
    }
}
