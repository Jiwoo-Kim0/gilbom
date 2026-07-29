package com.gilbom.robot.data.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MqttCredentialRevocationRequest(
        @NotBlank
        @Size(max = 100)
        String reason
) {

    public MqttCredentialRevocationRequest {
        reason = reason == null ? null : reason.trim();
    }
}
