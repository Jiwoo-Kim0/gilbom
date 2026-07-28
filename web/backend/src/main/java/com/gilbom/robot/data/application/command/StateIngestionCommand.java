package com.gilbom.robot.data.application.command;

import java.time.Instant;
import java.util.Objects;

import com.gilbom.robot.data.mqtt.v2.inbound.StatePayload;

public record StateIngestionCommand(
        StatePayload payload,
        String rawPayload,
        Instant receivedAt
) {

    public StateIngestionCommand {
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(rawPayload, "rawPayload must not be null");
        Objects.requireNonNull(receivedAt, "receivedAt must not be null");
    }
}

