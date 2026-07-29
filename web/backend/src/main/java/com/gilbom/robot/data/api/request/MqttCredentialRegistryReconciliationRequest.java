package com.gilbom.robot.data.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MqttCredentialRegistryReconciliationRequest(
        @NotBlank
        @Size(max = 100)
        String reason
) {

    public MqttCredentialRegistryReconciliationRequest {
        reason = reason == null ? null : reason.trim();
    }
}
