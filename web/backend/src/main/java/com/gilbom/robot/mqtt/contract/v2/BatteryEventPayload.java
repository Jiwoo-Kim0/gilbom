package com.gilbom.robot.mqtt.contract.v2;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;
import java.util.UUID;

public record BatteryEventPayload(
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
        @NotNull BatteryState previousState,
        @NotNull @Valid Battery battery,
        @DecimalMin("0.0") @DecimalMax("100.0") Double thresholdPct,
        @NotNull @Valid ActionTaken actionTaken
) implements MqttEventPayload {

    @AssertTrue(message = "battery event source_component must be power")
    public boolean isSourceValid() {
        return sourceComponent == null || sourceComponent == EventSource.POWER;
    }

    public record Battery(
            @NotNull Boolean valid,
            @DecimalMin("0.0") @DecimalMax("100.0") Double percent,
            @NotNull BatteryState state
    ) {

        @AssertTrue(message = "battery valid flag, percent and state are inconsistent")
        public boolean isValidityConsistent() {
            if (valid == null || state == null) {
                return true;
            }
            if (valid) {
                return percent != null && state != BatteryState.UNKNOWN;
            }
            return percent == null && state == BatteryState.UNKNOWN;
        }
    }

    public record ActionTaken(
            MissionState missionState,
            @NotNull RobotMode robotMode
    ) {
    }

    public enum EventType {
        BATTERY_LOW,
        BATTERY_CRITICAL,
        BATTERY_RECOVERED,
        BATTERY_SENSOR_FAULT,
        BATTERY_SENSOR_RECOVERED
    }
}
