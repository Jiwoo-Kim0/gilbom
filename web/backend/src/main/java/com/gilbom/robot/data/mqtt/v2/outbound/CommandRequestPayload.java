package com.gilbom.robot.data.mqtt.v2.outbound;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.gilbom.robot.data.mqtt.v2.common.MqttV2Payload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.UUID;

public record CommandRequestPayload(
        @NotBlank @Pattern(regexp = "2\\.0") String schemaVersion,
        @NotNull UUID commandId,
        @NotNull CommandType commandType,
        @NotNull Instant issuedAt,
        @NotNull Instant expiresAt,
        @NotNull @Valid RequestedBy requestedBy,
        @NotNull @Valid Parameters parameters
) implements MqttV2Payload {

    @AssertTrue(message = "expires_at must be later than issued_at")
    @JsonIgnore
    public boolean isExpiryValid() {
        return issuedAt == null || expiresAt == null || expiresAt.isAfter(issuedAt);
    }

    @AssertTrue(message = "parameters do not match command_type")
    @JsonIgnore
    public boolean isParametersValid() {
        if (commandType == null || parameters == null) {
            return true;
        }
        return switch (commandType) {
            case START_MISSION ->
                    hasText(parameters.missionId)
                            && hasText(parameters.zoneId)
                            && parameters.reason == null
                            && parameters.completionReason == null;
            case RESUME_MISSION ->
                    parameters.missionId == null
                            && parameters.zoneId == null
                            && parameters.reason == null
                            && parameters.completionReason == null;
            case COMPLETE_MISSION ->
                    hasText(parameters.missionId)
                            && parameters.zoneId == null
                            && parameters.reason == null
                            && hasText(parameters.completionReason);
            case CANCEL_MISSION ->
                    hasText(parameters.missionId)
                            && parameters.zoneId == null
                            && hasText(parameters.reason)
                            && parameters.completionReason == null;
            case PAUSE_MISSION, EMERGENCY_STOP, CLEAR_EMERGENCY_STOP ->
                    parameters.missionId == null
                            && parameters.zoneId == null
                            && hasText(parameters.reason)
                            && parameters.completionReason == null;
        };
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record RequestedBy(
            @NotNull ActorType actorType,
            @NotBlank String actorId
    ) {
    }

    public record Parameters(
            String missionId,
            String zoneId,
            String reason,
            String completionReason
    ) {
        public Parameters(
                String missionId,
                String zoneId,
                String reason
        ) {
            this(missionId, zoneId, reason, null);
        }
    }

    public enum CommandType {
        START_MISSION,
        PAUSE_MISSION,
        RESUME_MISSION,
        COMPLETE_MISSION,
        CANCEL_MISSION,
        EMERGENCY_STOP,
        CLEAR_EMERGENCY_STOP
    }

    public enum ActorType {
        ADMIN,
        SYSTEM
    }
}
