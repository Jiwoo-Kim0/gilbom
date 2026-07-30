package com.gilbom.robot.telemetry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.robot.application.RobotScopeService;
import com.gilbom.robot.data.api.response.RobotTelemetrySeriesPointResponse;
import com.gilbom.robot.data.api.response.RobotTelemetrySeriesResponse;

@Service
public class RobotTelemetrySeriesService {

    static final Duration MAXIMUM_DURATION = Duration.ofDays(31);
    static final long MAXIMUM_POINT_COUNT = 5_000;

    private static final String ROBOT_SQL = """
            SELECT
                r.robot_id,
                r.mqtt_id,
                r.robot_name
            FROM robot.robot r
            WHERE r.robot_id = :robotId
              AND
            """ + RobotScopeService.VISIBLE_ROBOT_PREDICATE;

    private static final String SERIES_SQL = """
            SELECT
                date_bin(
                    make_interval(secs => :bucketSeconds),
                    t.sampled_at,
                    TIMESTAMPTZ '1970-01-01 00:00:00+00'
                ) AS bucket_at,
                COUNT(*) AS sample_count,
                COUNT(*) FILTER (
                    WHERE t.battery_valid IS TRUE
                ) AS valid_battery_sample_count,
                ROUND(
                    AVG(t.battery_percent) FILTER (
                        WHERE t.battery_valid IS TRUE
                    ),
                    2
                ) AS average_battery_percent,
                MIN(t.battery_percent) FILTER (
                    WHERE t.battery_valid IS TRUE
                ) AS minimum_battery_percent,
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
                COUNT(*) FILTER (
                    WHERE t.position_valid IS TRUE
                ) AS valid_gps_sample_count,
                COUNT(*) FILTER (
                    WHERE t.position_valid IS FALSE
                ) AS invalid_gps_sample_count
            FROM telemetry.robot_telemetry t
            WHERE t.robot_id = :robotId
              AND t.sampled_at >= :from
              AND t.sampled_at < :to
            GROUP BY bucket_at
            ORDER BY bucket_at
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public RobotTelemetrySeriesService(
            NamedParameterJdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public RobotTelemetrySeriesResponse find(
            UUID userId,
            UUID robotId,
            Instant from,
            Instant to,
            String requestedInterval
    ) {
        SeriesInterval interval = SeriesInterval.parse(requestedInterval);
        validatePeriod(from, to, interval);

        Map<String, ?> robotParameters = Map.of(
                "userId", userId,
                "robotId", robotId);
        List<RobotIdentity> robots = jdbcTemplate.query(
                ROBOT_SQL,
                robotParameters,
                (resultSet, rowNumber) -> new RobotIdentity(
                        resultSet.getObject("robot_id", UUID.class),
                        resultSet.getString("mqtt_id"),
                        resultSet.getString("robot_name")));
        if (robots.isEmpty()) {
            throw new BusinessException(ErrorCode.ROBOT_NOT_FOUND);
        }

        Map<String, ?> seriesParameters = Map.of(
                "robotId", robotId,
                "from", from.atOffset(ZoneOffset.UTC),
                "to", to.atOffset(ZoneOffset.UTC),
                "bucketSeconds", interval.duration().toSeconds());
        List<RobotTelemetrySeriesPointResponse> points = jdbcTemplate.query(
                SERIES_SQL,
                seriesParameters,
                (resultSet, rowNumber) -> {
                    long sampleCount = resultSet.getLong("sample_count");
                    long validGpsSampleCount = resultSet.getLong(
                            "valid_gps_sample_count");
                    return new RobotTelemetrySeriesPointResponse(
                            resultSet.getObject(
                                    "bucket_at",
                                    OffsetDateTime.class).toInstant(),
                            sampleCount,
                            resultSet.getLong(
                                    "valid_battery_sample_count"),
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
                            validGpsSampleCount,
                            resultSet.getLong(
                                    "invalid_gps_sample_count"),
                            rate(validGpsSampleCount, sampleCount));
                });

        RobotIdentity robot = robots.getFirst();
        return new RobotTelemetrySeriesResponse(
                robot.robotId(),
                robot.mqttId(),
                robot.robotName(),
                from,
                to,
                interval.code(),
                points);
    }

    private static void validatePeriod(
            Instant from,
            Instant to,
            SeriesInterval interval
    ) {
        if (from == null || to == null) {
            throw new BusinessException(
                    ErrorCode.COMMON_INVALID_REQUEST,
                    "Telemetry 시계열 조회 시작 시각과 종료 시각은 필수입니다.");
        }
        Duration duration = Duration.between(from, to);
        if (duration.isNegative()
                || duration.isZero()
                || duration.compareTo(MAXIMUM_DURATION) > 0) {
            throw new BusinessException(
                    ErrorCode.STATISTICS_PERIOD_INVALID,
                    "Telemetry 시계열 조회 기간은 31일 이하여야 합니다.");
        }
        long pointCount = Math.ceilDiv(
                duration.toNanos(),
                interval.duration().toNanos());
        if (pointCount > MAXIMUM_POINT_COUNT) {
            throw new BusinessException(
                    ErrorCode.STATISTICS_PERIOD_INVALID,
                    "Telemetry 시계열은 최대 5,000개 구간까지만 "
                            + "조회할 수 있습니다.");
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

    private enum SeriesInterval {
        ONE_MINUTE("1m", Duration.ofMinutes(1)),
        FIVE_MINUTES("5m", Duration.ofMinutes(5)),
        FIFTEEN_MINUTES("15m", Duration.ofMinutes(15)),
        ONE_HOUR("1h", Duration.ofHours(1));

        private final String code;
        private final Duration duration;

        SeriesInterval(String code, Duration duration) {
            this.code = code;
            this.duration = duration;
        }

        private String code() {
            return code;
        }

        private Duration duration() {
            return duration;
        }

        private static SeriesInterval parse(String requestedInterval) {
            if (requestedInterval == null) {
                throw new BusinessException(
                        ErrorCode.COMMON_INVALID_REQUEST,
                        "Telemetry 시계열 집계 간격은 필수입니다.");
            }
            String normalized = requestedInterval
                    .trim()
                    .toLowerCase(Locale.ROOT);
            for (SeriesInterval interval : values()) {
                if (interval.code.equals(normalized)) {
                    return interval;
                }
            }
            throw new BusinessException(
                    ErrorCode.COMMON_INVALID_REQUEST,
                    "Telemetry 시계열 집계 간격은 "
                            + "1m, 5m, 15m, 1h 중 하나여야 합니다.");
        }
    }

    private record RobotIdentity(
            UUID robotId,
            String mqttId,
            String robotName
    ) {
    }
}
