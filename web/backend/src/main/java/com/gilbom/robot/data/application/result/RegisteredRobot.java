package com.gilbom.robot.data.application.result;

import java.util.UUID;

public record RegisteredRobot(
        UUID robotId,
        String robotCode,
        String robotName,
        String serialNumber,
        String operationalStatus,
        String connectivityStatus
) {
}
