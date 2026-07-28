package com.gilbom.robot.event;

import java.util.UUID;

public record RobotEventIngestionResult(
        UUID eventId,
        UUID robotId,
        UUID missionId,
        boolean inserted
) {
}
