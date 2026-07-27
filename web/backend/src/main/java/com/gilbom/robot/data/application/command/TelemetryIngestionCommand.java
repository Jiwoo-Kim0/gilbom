package com.gilbom.robot.data.application.command;

import java.time.Instant;
import java.util.Objects;

import com.gilbom.robot.data.mqtt.v2.inbound.TelemetryPayload;

public record TelemetryIngestionCommand(
        TelemetryPayload payload,
        String rawPayload,
        Instant receivedAt
) {

    public TelemetryIngestionCommand {
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(rawPayload, "rawPayload must not be null");
        Objects.requireNonNull(receivedAt, "receivedAt must not be null");
    }
}
