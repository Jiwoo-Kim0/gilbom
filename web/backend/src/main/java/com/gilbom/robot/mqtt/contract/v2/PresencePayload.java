package com.gilbom.robot.mqtt.contract.v2;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.UUID;

public record PresencePayload(
        @NotBlank @Pattern(regexp = "2\\.0") String schemaVersion,
        @NotBlank String robotId,
        @NotNull UUID bootId,
        @NotNull Status status,
        Instant connectedAt,
        OfflineReason reason
) implements RobotIdentifiedPayload {

    @AssertTrue(message = "ONLINE requires connected_at; OFFLINE requires reason")
    public boolean isStatusSpecificFieldsValid() {
        if (status == null) {
            return true;
        }
        return switch (status) {
            case ONLINE -> connectedAt != null && reason == null;
            case OFFLINE -> connectedAt == null && reason != null;
        };
    }

    public enum Status {
        ONLINE,
        OFFLINE
    }

    public enum OfflineReason {
        GRACEFUL_SHUTDOWN,
        CRITICAL_BATTERY_SHUTDOWN,
        SYSTEM_FAULT_SHUTDOWN,
        UNGRACEFUL_DISCONNECT
    }
}
