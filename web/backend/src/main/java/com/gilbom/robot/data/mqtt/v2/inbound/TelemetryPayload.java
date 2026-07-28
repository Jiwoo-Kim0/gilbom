package com.gilbom.robot.data.mqtt.v2.inbound;

import com.gilbom.robot.data.mqtt.v2.common.BatteryState;
import com.gilbom.robot.data.mqtt.v2.common.MissionState;
import com.gilbom.robot.data.mqtt.v2.common.RobotIdentifiedPayload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;
import java.util.UUID;

public record TelemetryPayload(
        @NotBlank @Pattern(regexp = "2\\.0") String schemaVersion,
        @NotNull UUID messageId,
        @NotBlank String mqttId,
        @NotNull UUID bootId,
        @NotNull @Positive Long seq,
        @NotNull Instant sampledAt,
        String missionId,
        @NotNull @Valid Position position,
        @NotNull @Valid Motion motion,
        @NotNull @Valid Battery battery,
        @NotNull @Valid SystemMetrics system,
        @Valid Mission mission
) implements RobotIdentifiedPayload {

    @AssertTrue(message = "mission_id and mission must be present or null together")
    public boolean isMissionPresenceConsistent() {
        return (missionId == null) == (mission == null);
    }

    public record Position(
            @NotNull Boolean valid,
            @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
            @DecimalMin("-180.0") @DecimalMax("180.0") Double lon,
            @DecimalMin("0.0") @DecimalMax(value = "360.0", inclusive = false) Double headingDeg
    ) {

        @AssertTrue(message = "position valid flag and coordinates are inconsistent")
        public boolean isValidityConsistent() {
            if (valid == null) {
                return true;
            }
            if (valid) {
                return lat != null && lon != null;
            }
            return lat == null && lon == null && headingDeg == null;
        }
    }

    public record Motion(
            @NotNull Boolean moving,
            @PositiveOrZero Double speedMps
    ) {
    }

    public record Battery(
            @NotNull Boolean valid,
            @DecimalMin("0.0") @DecimalMax("100.0") Double percent,
            @NotNull BatteryState state,
            Source source,
            Double voltageV,
            Double currentA,
            Boolean charging,
            Double batteryTemperatureC,
            @PositiveOrZero Integer estimatedRuntimeSec
    ) {

        @AssertTrue(message = "battery valid flag, percent, state and source are inconsistent")
        public boolean isValidityConsistent() {
            if (valid == null || state == null) {
                return true;
            }
            if (valid) {
                return percent != null && state != BatteryState.UNKNOWN && source != null;
            }
            return percent == null && state == BatteryState.UNKNOWN && source == null;
        }
    }

    public record SystemMetrics(
            Double jetsonTempC
    ) {
    }

    public record Mission(
            @NotNull MissionState state,
            @NotNull @PositiveOrZero Integer elapsedSec,
            @NotNull @PositiveOrZero Integer incidentCount
    ) {
    }

    public enum Source {
        MEASURED,
        ESTIMATED
    }
}
