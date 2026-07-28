package com.gilbom.robot.data.mqtt.v2.inbound;

import com.gilbom.robot.data.mqtt.v2.common.BatteryState;
import com.gilbom.robot.data.mqtt.v2.common.HealthStatus;
import com.gilbom.robot.data.mqtt.v2.common.MissionState;
import com.gilbom.robot.data.mqtt.v2.common.RobotIdentifiedPayload;
import com.gilbom.robot.data.mqtt.v2.common.RobotMode;
import com.gilbom.robot.data.mqtt.v2.common.RobotStateReason;

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

public record StatePayload(
        @NotBlank @Pattern(regexp = "2\\.0") String schemaVersion,
        @NotBlank String mqttId,
        @NotNull UUID bootId,
        @NotNull @PositiveOrZero Long seq,
        @NotNull Instant updatedAt,
        @NotNull RobotMode mode,
        @NotNull RobotStateReason reasonCode,
        @Valid Mission mission,
        @NotNull @Valid Battery battery,
        @NotNull @Valid Health health
) implements RobotIdentifiedPayload {

    public record Mission(
            @NotBlank String missionId,
            @NotNull MissionState state
    ) {
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

    public record Health(
            @NotNull HealthStatus camera,
            @NotNull HealthStatus lidar,
            @NotNull HealthStatus gps,
            @NotNull HealthStatus battery,
            @NotNull HealthStatus motor,
            @NotNull HealthStatus storage
    ) {
    }
}
