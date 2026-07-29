package com.gilbom.robot.data.mqtt.v2.outbound;

import com.gilbom.robot.data.mqtt.v2.common.MqttV2Payload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.UUID;

public record IngestReceiptPayload(
        @NotBlank @Pattern(regexp = "2\\.0") String schemaVersion,
        @NotNull UUID eventId,
        @NotNull Status ingestStatus,
        @Pattern(regexp = "^INC-[0-9]{8}-[0-9]{6}$") String incidentId,
        @NotNull Instant receivedAt
) implements MqttV2Payload {

    public enum Status {
        STORED,
        MERGED,
        DUPLICATE,
        REJECTED
    }
}
