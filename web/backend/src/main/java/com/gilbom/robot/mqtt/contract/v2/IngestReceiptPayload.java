package com.gilbom.robot.mqtt.contract.v2;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.UUID;

public record IngestReceiptPayload(
        @NotBlank @Pattern(regexp = "2\\.0") String schemaVersion,
        @NotNull UUID eventId,
        @NotNull Status ingestStatus,
        String incidentId,
        @NotNull Instant receivedAt
) implements MqttV2Payload {

    public enum Status {
        STORED,
        MERGED,
        DUPLICATE,
        REJECTED
    }
}
