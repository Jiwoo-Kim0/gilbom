package com.gilbom.robot.data.api.response;

import java.time.Instant;
import java.util.UUID;

public record MqttCredentialIssuanceResponse(
        UUID credentialId,
        String credentialType,
        MqttConnection mqtt,
        Instant expiresAt
) {

    public record MqttConnection(
            String brokerUri,
            String username,
            String password,
            String clientId
    ) {
    }
}
