package com.gilbom.robot.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gilbom.robot.realtime.RobotRealtimeEvent;

@Service
public class RobotOfflineDetectionService {

    private static final String TIMEOUT_PAYLOAD =
            "{\"reason\":\"HEARTBEAT_TIMEOUT\"}";

    private final JdbcTemplate jdbcTemplate;
    private final ApplicationEventPublisher eventPublisher;

    public RobotOfflineDetectionService(
            JdbcTemplate jdbcTemplate,
            ApplicationEventPublisher eventPublisher
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public int markTimedOut(Instant cutoff, Instant detectedAt) {
        OffsetDateTime cutoffUtc = utc(cutoff);
        OffsetDateTime detectedAtUtc = utc(detectedAt);
        List<TimedOutRobot> timedOutRobots = jdbcTemplate.query("""
                SELECT
                    state.robot_id,
                    state.current_boot_id AS boot_id,
                    robot.mqtt_id
                FROM telemetry.robot_current_state state
                JOIN telemetry.robot_boot_session session
                  ON session.robot_id = state.robot_id
                 AND session.boot_id = state.current_boot_id
                JOIN robot.robot robot
                  ON robot.robot_id = state.robot_id
                WHERE state.connectivity_status = 'ONLINE'
                  AND session.session_status = 'ONLINE'
                  AND session.last_received_at < ?
                  AND robot.operational_status <> 'DISABLED'
                FOR UPDATE OF state, session SKIP LOCKED
                """,
                (resultSet, rowNumber) -> new TimedOutRobot(
                        resultSet.getObject("robot_id", UUID.class),
                        resultSet.getObject("boot_id", UUID.class),
                        resultSet.getString("mqtt_id")),
                cutoffUtc);

        int changed = 0;
        for (TimedOutRobot robot : timedOutRobots) {
            int sessionUpdated = jdbcTemplate.update("""
                    UPDATE telemetry.robot_boot_session
                    SET session_status = 'OFFLINE',
                        offline_received_at = ?,
                        offline_reason = 'HEARTBEAT_TIMEOUT',
                        updated_at = GREATEST(updated_at, ?)
                    WHERE robot_id = ?
                      AND boot_id = ?
                      AND session_status = 'ONLINE'
                    """,
                    detectedAtUtc,
                    detectedAtUtc,
                    robot.robotId(),
                    robot.bootId());
            if (sessionUpdated == 0) {
                continue;
            }

            int currentUpdated = jdbcTemplate.update("""
                    UPDATE telemetry.robot_current_state
                    SET connectivity_status = 'OFFLINE',
                        connection_changed_at = ?,
                        updated_at = GREATEST(updated_at, ?)
                    WHERE robot_id = ?
                      AND current_boot_id = ?
                      AND connectivity_status = 'ONLINE'
                    """,
                    detectedAtUtc,
                    detectedAtUtc,
                    robot.robotId(),
                    robot.bootId());
            if (currentUpdated != 1) {
                throw new IllegalStateException(
                        "Timed-out boot is not the current robot state");
            }

            jdbcTemplate.update("""
                    INSERT INTO telemetry.robot_status_history (
                        robot_id,
                        boot_id,
                        event_kind,
                        connectivity_status,
                        reason_code,
                        observed_at,
                        received_at,
                        raw_payload
                    ) VALUES (
                        ?, ?, 'TIMEOUT', 'OFFLINE', 'HEARTBEAT_TIMEOUT',
                        ?, ?, CAST(? AS JSONB)
                    )
                    """,
                    robot.robotId(),
                    robot.bootId(),
                    detectedAtUtc,
                    detectedAtUtc,
                    TIMEOUT_PAYLOAD);
            eventPublisher.publishEvent(
                    RobotRealtimeEvent.connectivityChanged(
                            robot.robotId(),
                            robot.mqttId(),
                            detectedAt));
            changed++;
        }
        return changed;
    }

    private OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record TimedOutRobot(
            UUID robotId,
            UUID bootId,
            String mqttId
    ) {
    }
}
