package com.gilbom.robot.data.mqtt.v2.inbound;

import com.gilbom.robot.data.mqtt.v2.common.RobotIdentifiedPayload;
import com.gilbom.robot.data.mqtt.v2.common.RobotMode;
import com.gilbom.robot.data.mqtt.v2.common.RobotStateReason;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.UUID;

public record CommandResultPayload(
        @NotBlank @Pattern(regexp = "2\\.0") String schemaVersion,
        @NotNull UUID resultId,
        @NotNull UUID commandId,
        @NotBlank String mqttId,
        @NotNull Status status,
        @NotNull Instant receivedAt,
        Instant completedAt,
        @NotBlank String reasonCode,
        @NotNull @Valid State state
) implements RobotIdentifiedPayload {

    @AssertTrue(message = "ACCEPTED must omit completed_at; final status requires it")
    public boolean isCompletionTimePresenceValid() {
        if (status == null) {
            return true;
        }
        return status == Status.ACCEPTED
                ? completedAt == null
                : completedAt != null;
    }

    @AssertTrue(message = "completed_at must not be earlier than received_at")
    public boolean isCompletionTimeChronological() {
        return receivedAt == null
                || completedAt == null
                || !completedAt.isBefore(receivedAt);
    }

    public record State(
            @NotNull RobotMode mode,
            @NotNull RobotStateReason reasonCode
    ) {
    }

    public enum Status {
        ACCEPTED,
        COMPLETED,
        FAILED,
        REJECTED,
        EXPIRED
    }
}
