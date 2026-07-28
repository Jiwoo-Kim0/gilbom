package com.gilbom.robot.data.api.response;

import java.time.Instant;
import java.util.UUID;

public record RobotMapMarkerResponse(
        UUID robotId,
        String mqttId,
        String robotName,
        String connectivityStatus,
        Double latitude,
        Double longitude,
        Double headingDeg,
        String missionStatus,
        Instant observedAt
) {
}
