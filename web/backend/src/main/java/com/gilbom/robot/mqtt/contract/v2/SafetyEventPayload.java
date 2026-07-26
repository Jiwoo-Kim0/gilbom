package com.gilbom.robot.mqtt.contract.v2;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;
import java.util.UUID;

public record SafetyEventPayload(
        @NotBlank @Pattern(regexp = "2\\.0") String schemaVersion,
        @NotNull UUID eventId,
        @NotNull EventType eventType,
        @NotBlank String robotId,
        @NotNull UUID bootId,
        @NotNull @PositiveOrZero Long seq,
        @NotNull EventSource sourceComponent,
        @NotNull Instant occurredAt,
        @NotNull Instant sentAt,
        String missionId,
        @NotNull ReasonCode reasonCode,
        @NotNull @Valid Measurement measurement,
        @NotNull @Valid Transition transition,
        @NotNull Boolean autoResumeAllowed
) implements MqttEventPayload {

    @AssertTrue(message = "safety event source_component must be safety")
    public boolean isSourceValid() {
        return sourceComponent == null || sourceComponent == EventSource.SAFETY;
    }

    public record Measurement(
            @PositiveOrZero Double distanceM
    ) {
    }

    public record Transition(
            @NotNull RobotMode modeBefore,
            @NotNull RobotMode modeAfter
    ) {
    }

    public enum EventType {
        SAFETY_STOPPED,
        SAFETY_CLEARED,
        SENSOR_FAULT,
        SENSOR_RECOVERED
    }

    public enum ReasonCode {
        OBSTACLE,
        BOUNDARY_LOST,
        REMOTE_ESTOP,
        LOW_BATTERY,
        CRITICAL_BATTERY,
        LIDAR_FAULT,
        MOTOR_FAULT,
        INTERNAL_ERROR
    }
}
