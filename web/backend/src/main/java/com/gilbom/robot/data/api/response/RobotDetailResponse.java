package com.gilbom.robot.data.api.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RobotDetailResponse(
        UUID robotId,
        String mqttId,
        String robotName,
        String serialNumber,
        String operationalStatus,
        String connectivityStatus,
        Instant connectionChangedAt,
        Instant lastReceivedAt,
        String robotMode,
        String stateReasonCode,
        String missionStatus,
        BigDecimal batteryPercent,
        String batteryState,
        Boolean charging,
        Double batteryTemperatureC,
        Integer estimatedRuntimeSec,
        String cameraHealth,
        String lidarHealth,
        String gpsHealth,
        String motorHealth,
        String storageHealth,
        Double latitude,
        Double longitude,
        Double headingDeg,
        Boolean moving,
        Double speedMps,
        Double jetsonTempC,
        String overallHealthStatus,
        Instant telemetrySampledAt,
        Instant telemetryReceivedAt,
        Instant registeredAt,
        Instant updatedAt
) {
}
