package com.gilbom.robot.data.application.result;

import java.time.Instant;
import java.util.UUID;

public record IssuedMqttCredential(
        UUID credentialId,
        String credentialType,
        String brokerUri,
        String username,
        String password,
        String clientId,
        Instant expiresAt
) {
}
