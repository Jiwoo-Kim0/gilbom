package com.gilbom.robot.data.mqtt.v2.inbound;

import com.gilbom.robot.data.mqtt.v2.common.EventSource;
import com.gilbom.robot.data.mqtt.v2.common.MqttEventPayload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;

public record MissionEventPayload(
        @NotBlank @Pattern(regexp = "2\\.0") String schemaVersion,
        @NotNull UUID eventId,
        @NotNull EventType eventType,
        @NotBlank String robotId,
        @NotNull UUID bootId,
        @NotNull @PositiveOrZero Long seq,
        @NotNull EventSource sourceComponent,
        @NotNull Instant occurredAt,
        @NotNull Instant sentAt,
        @NotBlank String missionId,
        @Valid Result result
) implements MqttEventPayload {

    private static final EnumSet<EventType> FINAL_EVENTS = EnumSet.of(
            EventType.MISSION_COMPLETED,
            EventType.MISSION_CANCELLED,
            EventType.MISSION_FAILED
    );

    @AssertTrue(message = "mission event source_component must be mission")
    public boolean isSourceValid() {
        return sourceComponent == null || sourceComponent == EventSource.MISSION;
    }

    @AssertTrue(message = "final mission events require result; other mission events omit it")
    public boolean isResultPresenceValid() {
        if (eventType == null) {
            return true;
        }
        return FINAL_EVENTS.contains(eventType) == (result != null);
    }

    public record Result(
            @NotNull @PositiveOrZero Integer durationSec,
            @NotNull @PositiveOrZero Integer incidentCount,
            @NotNull @PositiveOrZero Integer normalDetectionCount,
            @NotBlank String completionReason
    ) {
    }

    public enum EventType {
        MISSION_STARTED,
        MISSION_PAUSED,
        MISSION_RESUMED,
        MISSION_COMPLETED,
        MISSION_CANCELLED,
        MISSION_FAILED
    }
}
