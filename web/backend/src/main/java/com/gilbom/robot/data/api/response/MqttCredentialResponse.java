package com.gilbom.robot.data.api.response;

import java.time.Instant;
import java.util.UUID;

public record MqttCredentialResponse(
        UUID credentialId,
        String credentialType,
        int credentialVersion,
        String credentialStatus,
        UUID issuedBy,
        Instant issuedAt,
        Instant expiresAt,
        Instant activatedAt,
        UUID revokedBy,
        Instant revokedAt,
        String revocationReason
) {
}
