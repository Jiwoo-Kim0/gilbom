package com.gilbom.robot.data.api.response;

import java.time.Instant;
import java.util.UUID;

public record MqttCredentialIssuanceResponse(
        UUID robotId,
        String mqttId,
        UUID credentialId,
        String credentialType,
        MqttConnection mqtt,
        ImageUpload imageUpload,
        Instant expiresAt
) {

    public record MqttConnection(
            String brokerUri,
            String username,
            String password,
            String clientId
    ) {
    }

    public record ImageUpload(
            String url,
            String apiKey
    ) {
    }
}
