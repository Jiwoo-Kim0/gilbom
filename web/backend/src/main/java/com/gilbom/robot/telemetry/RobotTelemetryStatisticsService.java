package com.gilbom.robot.telemetry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.robot.application.RobotScopeService;
import com.gilbom.robot.data.api.response.RobotTelemetryStatisticsResponse;

@Service
public class RobotTelemetryStatisticsService {

    static final Duration MAXIMUM_DURATION = Duration.ofDays(31);

    private static final String STATISTICS_SQL = """
            SELECT
                r.robot_id,
                r.mqtt_id,
                r.robot_name,
                COUNT(t.message_id) AS sample_count,
                COUNT(t.message_id) FILTER (
                    WHERE t.battery_valid IS TRUE
                ) AS valid_battery_sample_count,
                ROUND(
                    AVG(t.battery_percent),
                    2
                ) AS average_battery_percent,
                MIN(t.battery_percent) AS minimum_battery_percent,
                ROUND(
                    CAST(AVG(t.jetson_temp_c) AS NUMERIC),
                    2
                ) AS average_jetson_temperature_c,
                ROUND(
                    CAST(MAX(t.jetson_temp_c) AS NUMERIC),
                    2
                ) AS maximum_jetson_temperature_c,
                ROUND(
                    CAST(AVG(t.speed_mps) AS NUMERIC),
                    2
                ) AS average_speed_mps,
                ROUND(
                    CAST(MAX(t.speed_mps) AS NUMERIC),
                    2
                ) AS maximum_speed_mps,
                COUNT(t.message_id) FILTER (
                    WHERE t.position_valid IS TRUE
                ) AS valid_gps_sample_count,
                COUNT(t.message_id) FILTER (
                    WHERE t.position_valid IS FALSE
                ) AS invalid_gps_sample_count,
                MAX(t.sampled_at) AS last_sampled_at
            FROM robot.robot r
            LEFT JOIN telemetry.robot_telemetry t
              ON t.robot_id = r.robot_id
             AND t.sampled_at >= :from
             AND t.sampled_at < :to
            WHERE r.robot_id = :robotId
              AND
            """ + RobotScopeService.VISIBLE_ROBOT_PREDICATE + """
            GROUP BY
                r.robot_id,
                r.mqtt_id,
                r.robot_name
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public RobotTelemetryStatisticsService(
            NamedParameterJdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public RobotTelemetryStatisticsResponse find(
            UUID userId,
            UUID robotId,
            Instant from,
            Instant to
    ) {
        validatePeriod(from, to);
        Map<String, ?> parameters = Map.of(
                "userId", userId,
                "robotId", robotId,
                "from", from.atOffset(ZoneOffset.UTC),
                "to", to.atOffset(ZoneOffset.UTC));
        List<RobotTelemetryStatisticsRow> rows = jdbcTemplate.query(
                STATISTICS_SQL,
                parameters,
                (resultSet, rowNumber) -> new RobotTelemetryStatisticsRow(
                        resultSet.getObject("robot_id", UUID.class),
                        resultSet.getString("mqtt_id"),
                        resultSet.getString("robot_name"),
                        resultSet.getLong("sample_count"),
                        resultSet.getLong("valid_battery_sample_count"),
                        resultSet.getBigDecimal(
                                "average_battery_percent"),
                        resultSet.getBigDecimal(
                                "minimum_battery_percent"),
                        resultSet.getBigDecimal(
                                "average_jetson_temperature_c"),
                        resultSet.getBigDecimal(
                                "maximum_jetson_temperature_c"),
                        resultSet.getBigDecimal("average_speed_mps"),
                        resultSet.getBigDecimal("maximum_speed_mps"),
                        resultSet.getLong("valid_gps_sample_count"),
                        resultSet.getLong("invalid_gps_sample_count"),
                        resultSet.getObject(
                                "last_sampled_at",
                                java.time.OffsetDateTime.class)));
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.ROBOT_NOT_FOUND);
        }

        RobotTelemetryStatisticsRow row = rows.getFirst();
        return new RobotTelemetryStatisticsResponse(
                row.robotId(),
                row.mqttId(),
                row.robotName(),
                from,
                to,
                row.sampleCount(),
                row.validBatterySampleCount(),
                row.averageBatteryPercent(),
                row.minimumBatteryPercent(),
                row.averageJetsonTemperatureC(),
                row.maximumJetsonTemperatureC(),
                row.averageSpeedMps(),
                row.maximumSpeedMps(),
                row.validGpsSampleCount(),
                row.invalidGpsSampleCount(),
                rate(row.validGpsSampleCount(), row.sampleCount()),
                row.lastSampledAt() == null
                        ? null
                        : row.lastSampledAt().toInstant());
    }

    private static void validatePeriod(Instant from, Instant to) {
        if (from == null || to == null) {
            throw new BusinessException(
                    ErrorCode.COMMON_INVALID_REQUEST,
                    "Telemetry 통계 조회 시작 시각과 종료 시각은 필수입니다.");
        }
        if (!from.isBefore(to)
                || Duration.between(from, to)
                        .compareTo(MAXIMUM_DURATION) > 0) {
            throw new BusinessException(
                    ErrorCode.STATISTICS_PERIOD_INVALID,
                    "Telemetry 통계 조회 기간은 31일 이하여야 합니다.");
        }
    }

    private static BigDecimal rate(long count, long total) {
        if (total == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return BigDecimal.valueOf(count)
                .multiply(BigDecimal.valueOf(100))
                .divide(
                        BigDecimal.valueOf(total),
                        2,
                        RoundingMode.HALF_UP);
    }

    private record RobotTelemetryStatisticsRow(
            UUID robotId,
            String mqttId,
            String robotName,
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
            java.time.OffsetDateTime lastSampledAt
    ) {
    }
}
