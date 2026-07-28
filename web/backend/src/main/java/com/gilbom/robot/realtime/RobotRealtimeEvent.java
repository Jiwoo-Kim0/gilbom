package com.gilbom.robot.realtime;

import java.time.Instant;
import java.util.UUID;

public record RobotRealtimeEvent(
        UUID robotId,
        String mqttId,
        String eventType,
        Instant observedAt
) {

    public static RobotRealtimeEvent telemetryUpdated(
            UUID robotId,
            String mqttId,
            Instant observedAt
    ) {
        return new RobotRealtimeEvent(
                robotId,
                mqttId,
                "ROBOT_TELEMETRY_UPDATED",
                observedAt);
    }

    public static RobotRealtimeEvent connectivityChanged(
            UUID robotId,
            String mqttId,
            Instant observedAt
    ) {
        return new RobotRealtimeEvent(
                robotId,
                mqttId,
                "ROBOT_CONNECTIVITY_CHANGED",
                observedAt);
    }

    public static RobotRealtimeEvent stateUpdated(
            UUID robotId,
            String mqttId,
            Instant observedAt
    ) {
        return new RobotRealtimeEvent(
                robotId,
                mqttId,
                "ROBOT_STATE_UPDATED",
                observedAt);
    }

    public static RobotRealtimeEvent commandStatusChanged(
            UUID robotId,
            String mqttId,
            Instant observedAt
    ) {
        return new RobotRealtimeEvent(
                robotId,
                mqttId,
                "COMMAND_STATUS_CHANGED",
                observedAt);
    }
}
