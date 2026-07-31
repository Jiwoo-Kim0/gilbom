package com.gilbom.robot.data.api.response;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 한 시간 구간에 포함된 MQTT v2 Telemetry 집계 지점이다.
 */
public record RobotTelemetrySeriesPointResponse(
        Instant bucketAt,
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
        BigDecimal gpsValidRatePercent
) {
}
