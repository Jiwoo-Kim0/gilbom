package com.gilbom.robot.data.api.response;

import java.time.Instant;
import java.util.UUID;

public record MqttCredentialRegistryStatusResponse(
        UUID robotId,
        String mqttId,
        String registryStatus,
        boolean driftDetected,
        UUID activeCredentialId,
        Integer activeCredentialVersion,
        Instant checkedAt
) {
}
