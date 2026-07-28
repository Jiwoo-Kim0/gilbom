package com.gilbom.robot.realtime;

import java.time.Instant;
import java.util.UUID;

public record RobotSseEventResponse(
        String eventType,
        UUID robotId,
        String mqttId,
        Instant observedAt
) {

    static RobotSseEventResponse from(RobotRealtimeEvent event) {
        return new RobotSseEventResponse(
                event.eventType(),
                event.robotId(),
                event.mqttId(),
                event.observedAt());
    }
}
