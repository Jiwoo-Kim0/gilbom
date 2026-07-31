package com.gilbom.robot.data.api.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 한 로봇의 MQTT v2 Telemetry를 고정 시간 구간으로 집계한 응답이다.
 */
public record RobotTelemetrySeriesResponse(
        UUID robotId,
        String mqttId,
        String robotName,
        Instant from,
        Instant to,
        String interval,
        List<RobotTelemetrySeriesPointResponse> points
) {
}
