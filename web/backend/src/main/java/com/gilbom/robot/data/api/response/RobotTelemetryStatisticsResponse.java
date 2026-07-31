package com.gilbom.robot.data.api.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 한 로봇의 MQTT v2 Telemetry 이력을 기간 단위로 집계한 응답이다.
 */
public record RobotTelemetryStatisticsResponse(
        UUID robotId,
        String mqttId,
        String robotName,
        Instant from,
        Instant to,
        long sampleCount,
        long validBatterySampleCount,
        BigDecimal averageBatteryPercent,
        BigDecimal minimumBatteryPercent,
        BigDecimal averageJetsonTemperatureC,
        BigDecimal maximumJetsonTemperatureC,
        BigDecimal averageSpeedMps,
        BigDecimal maximumSpeedMps,
        long validGpsSampleCount,
        long invalidGpsSampleCount,
        BigDecimal gpsValidRatePercent,
        Instant lastSampledAt
) {
}
