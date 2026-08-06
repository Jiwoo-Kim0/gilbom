package com.gilbom.robot.data.api.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.gilbom.robot.data.mqtt.v2.outbound.CommandRequestPayload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RobotCommandCreateRequest(
        @NotNull CommandRequestPayload.CommandType commandType,
        @NotNull @Valid Parameters parameters
) {

    @AssertTrue(message = "parameters do not match commandType")
    @JsonIgnore
    public boolean isParametersValid() {
        if (commandType == null || parameters == null) {
            return true;
        }
        return switch (commandType) {
            case START_MISSION ->
                    hasText(parameters.missionId())
                            && hasText(parameters.zoneId())
                            && parameters.reason() == null
                            && parameters.completionReason() == null;
            case RESUME_MISSION, COMPLETE_MISSION ->
                    parameters.missionId() == null
                            && parameters.zoneId() == null
                            && parameters.reason() == null
                            && parameters.completionReason() == null;
            case PAUSE_MISSION,
                    CANCEL_MISSION,
                    EMERGENCY_STOP,
                    CLEAR_EMERGENCY_STOP ->
                    parameters.missionId() == null
                            && parameters.zoneId() == null
                            && hasText(parameters.reason())
                            && parameters.completionReason() == null;
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record Parameters(
            @Size(max = 100) String missionId,
            @Size(max = 100) String zoneId,
            @Size(max = 500) String reason,
            @Size(max = 100) String completionReason
    ) {
        public Parameters(
                String missionId,
                String zoneId,
                String reason
        ) {
            this(missionId, zoneId, reason, null);
        }
    }
}
