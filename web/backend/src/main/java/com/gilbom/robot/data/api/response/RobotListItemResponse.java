package com.gilbom.robot.data.api.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RobotListItemResponse(
        UUID robotId,
        String mqttId,
        String robotName,
        String serialNumber,
        String operationalStatus,
        String connectivityStatus,
        Instant lastReceivedAt,
        BigDecimal batteryPercent,
        Boolean charging,
        Double jetsonTempC,
        Double speedMps,
        String robotMode,
        String missionStatus,
        String overallHealthStatus
) {
}
