package com.gilbom.robot.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.robot.data.application.command.PresenceIngestionCommand;
import com.gilbom.robot.data.application.command.StateIngestionCommand;
import com.gilbom.robot.data.application.result.RobotStatusIngestionResult;
import com.gilbom.robot.data.mqtt.v2.common.HealthStatus;
import com.gilbom.robot.data.mqtt.v2.inbound.PresencePayload;
import com.gilbom.robot.data.mqtt.v2.inbound.StatePayload;
import com.gilbom.robot.mqtt.replay.MqttReplayProtectionService;
import com.gilbom.robot.mqtt.replay.MqttReplayProtectionService.ClaimResult;
import com.gilbom.robot.mqtt.replay.MqttReplayProtectionService.SequenceKind;
import com.gilbom.robot.mqtt.topic.MqttTopicType;
import com.gilbom.robot.realtime.RobotRealtimeEvent;

@Service
public class RobotStatusIngestionService {

    private final JdbcTemplate jdbcTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final MqttReplayProtectionService replayProtectionService;

    public RobotStatusIngestionService(
            JdbcTemplate jdbcTemplate,
            ApplicationEventPublisher eventPublisher,
            MqttReplayProtectionService replayProtectionService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.eventPublisher = eventPublisher;
        this.replayProtectionService = replayProtectionService;
    }

    @Transactional
    public RobotStatusIngestionResult ingestPresence(
            PresenceIngestionCommand command
    ) {
        PresencePayload payload = command.payload();
        RobotReference robot = findRobot(payload.mqttId());
        CurrentState current = lockCurrentState(robot.robotId());
        assertBootOwnership(robot.robotId(), payload.bootId());

        OffsetDateTime receivedAt = utc(command.receivedAt());
        return switch (payload.status()) {
            case ONLINE -> ingestOnlinePresence(
                    robot,
                    current,
                    payload,
                    command.rawPayload(),
                    receivedAt);
            case OFFLINE -> ingestOfflinePresence(
                    robot,
                    current,
                    payload,
                    command.rawPayload(),
                    receivedAt);
        };
    }

    @Transactional
    public RobotStatusIngestionResult ingestState(StateIngestionCommand command) {
        StatePayload payload = command.payload();
        RobotReference robot = findRobot(payload.mqttId());
        CurrentState current = lockCurrentState(robot.robotId());
        assertBootOwnership(robot.robotId(), payload.bootId());

        OffsetDateTime observedAt = utc(payload.updatedAt());
        OffsetDateTime receivedAt = utc(command.receivedAt());
        ClaimResult claim = replayProtectionService.claim(
                MqttTopicType.STATE,
                SequenceKind.STATE,
                robot.robotId(),
                payload.bootId(),
                payload.seq(),
                null,
                payload.schemaVersion(),
                command.rawPayload(),
                command.receivedAt());
        if (claim == ClaimResult.DUPLICATE) {
            return new RobotStatusIngestionResult(false, false);
        }

        int historyInserted = insertStateHistory(
                robot.robotId(),
                payload,
                command.rawPayload(),
                observedAt,
                receivedAt);
        if (historyInserted == 0) {
            throw new BusinessException(
                    ErrorCode.MQTT_SEQUENCE_CONFLICT);
        }

        boolean currentStateUpdated = shouldUpdateState(
                current,
                payload.bootId(),
                payload.seq(),
                observedAt)
                && updateCurrentState(
                        robot.robotId(),
                        payload,
                        observedAt,
                        receivedAt);

        if (!currentStateUpdated
                && payload.bootId().equals(current.bootId())) {
            updateCurrentLastReceived(
                    robot.robotId(),
                    payload.bootId(),
                    receivedAt);
        }

        if (currentStateUpdated) {
            eventPublisher.publishEvent(RobotRealtimeEvent.stateUpdated(
                    robot.robotId(),
                    robot.mqttId(),
                    payload.updatedAt()));
        }
        return new RobotStatusIngestionResult(true, currentStateUpdated);
    }

    private RobotStatusIngestionResult ingestOnlinePresence(
            RobotReference robot,
            CurrentState current,
            PresencePayload payload,
            String rawPayload,
            OffsetDateTime receivedAt
    ) {
        OffsetDateTime connectedAt = utc(payload.connectedAt());
        boolean sessionChanged = transitionBootOnline(
                robot.robotId(),
                payload,
                connectedAt,
                receivedAt);
        BootSession incomingSession = findBootSession(payload.bootId());
        if (!"ONLINE".equals(incomingSession.status())) {
            return new RobotStatusIngestionResult(false, false);
        }

        boolean sameBoot = payload.bootId().equals(current.bootId());
        boolean newerBoot = current.bootId() == null
                || sameBoot
                || incomingSession.orderAt().isAfter(
                        findBootSession(current.bootId()).orderAt());
        boolean currentChanged = newerBoot
                && (!sameBoot
                    || !"ONLINE".equals(current.connectivityStatus())
                    || sessionChanged);

        if (!currentChanged) {
            return new RobotStatusIngestionResult(false, false);
        }

        if (!sameBoot) {
            resetCurrentMeasurementsForBootSwitch(robot.robotId());
        }
        updateCurrentConnectivity(
                robot.robotId(),
                payload.bootId(),
                "ONLINE",
                receivedAt);
        if (!sameBoot && current.bootId() != null) {
            closeReplacedBoot(
                    robot.robotId(),
                    current.bootId(),
                    receivedAt);
        }
        insertPresenceHistory(
                robot.robotId(),
                payload.bootId(),
                "ONLINE",
                null,
                rawPayload,
                connectedAt,
                receivedAt);
        eventPublisher.publishEvent(RobotRealtimeEvent.connectivityChanged(
                robot.robotId(),
                robot.mqttId(),
                payload.connectedAt()));
        return new RobotStatusIngestionResult(true, true);
    }

    private RobotStatusIngestionResult ingestOfflinePresence(
            RobotReference robot,
            CurrentState current,
            PresencePayload payload,
            String rawPayload,
            OffsetDateTime receivedAt
    ) {
        boolean sessionChanged = transitionBootOffline(
                robot.robotId(),
                payload,
                receivedAt);
        boolean currentChanged = sessionChanged
                && payload.bootId().equals(current.bootId())
                && "ONLINE".equals(current.connectivityStatus());
        if (!currentChanged) {
            return new RobotStatusIngestionResult(false, false);
        }

        updateCurrentConnectivity(
                robot.robotId(),
                payload.bootId(),
                "OFFLINE",
                receivedAt);
        insertPresenceHistory(
                robot.robotId(),
                payload.bootId(),
                "OFFLINE",
                payload.reason().name(),
                rawPayload,
                receivedAt,
                receivedAt);
        eventPublisher.publishEvent(RobotRealtimeEvent.connectivityChanged(
                robot.robotId(),
                robot.mqttId(),
                commandObservedAt(receivedAt)));
        return new RobotStatusIngestionResult(true, true);
    }

    private boolean transitionBootOnline(
            UUID robotId,
            PresencePayload payload,
            OffsetDateTime connectedAt,
            OffsetDateTime receivedAt
    ) {
        int changed = jdbcTemplate.update("""
                INSERT INTO telemetry.robot_boot_session (
                    boot_id,
                    robot_id,
                    schema_version,
                    session_status,
                    robot_connected_at,
                    first_received_at,
                    last_received_at,
                    created_at,
                    updated_at
                ) VALUES (?, ?, ?, 'ONLINE', ?, ?, ?, ?, ?)
                ON CONFLICT (boot_id) DO UPDATE
                SET schema_version = EXCLUDED.schema_version,
                    session_status = 'ONLINE',
                    robot_connected_at = EXCLUDED.robot_connected_at,
                    last_received_at = GREATEST(
                        telemetry.robot_boot_session.last_received_at,
                        EXCLUDED.last_received_at
                    ),
                    offline_received_at = NULL,
                    offline_reason = NULL,
                    updated_at = GREATEST(
                        telemetry.robot_boot_session.updated_at,
                        EXCLUDED.updated_at
                    )
                WHERE telemetry.robot_boot_session.robot_id = EXCLUDED.robot_id
                  AND (
                      telemetry.robot_boot_session.session_status = 'ONLINE'
                      OR telemetry.robot_boot_session.offline_reason IN (
                          'UNGRACEFUL_DISCONNECT',
                          'HEARTBEAT_TIMEOUT'
                      )
                  )
                  AND (
                      telemetry.robot_boot_session.robot_connected_at IS NULL
                      OR EXCLUDED.robot_connected_at
                          > telemetry.robot_boot_session.robot_connected_at
                  )
                """,
                payload.bootId(),
                robotId,
                payload.schemaVersion(),
                connectedAt,
                receivedAt,
                receivedAt,
                receivedAt,
                receivedAt);
        assertBootOwnership(robotId, payload.bootId());
        return changed == 1;
    }

    private boolean transitionBootOffline(
            UUID robotId,
            PresencePayload payload,
            OffsetDateTime receivedAt
    ) {
        int changed = jdbcTemplate.update("""
                INSERT INTO telemetry.robot_boot_session (
                    boot_id,
                    robot_id,
                    schema_version,
                    session_status,
                    first_received_at,
                    last_received_at,
                    offline_received_at,
                    offline_reason,
                    created_at,
                    updated_at
                ) VALUES (?, ?, ?, 'OFFLINE', ?, ?, ?, ?, ?, ?)
                ON CONFLICT (boot_id) DO UPDATE
                SET last_received_at = GREATEST(
                        telemetry.robot_boot_session.last_received_at,
                        EXCLUDED.last_received_at
                    ),
                    session_status = 'OFFLINE',
                    offline_received_at = EXCLUDED.offline_received_at,
                    offline_reason = EXCLUDED.offline_reason,
                    updated_at = GREATEST(
                        telemetry.robot_boot_session.updated_at,
                        EXCLUDED.updated_at
                    )
                WHERE telemetry.robot_boot_session.robot_id = EXCLUDED.robot_id
                  AND telemetry.robot_boot_session.session_status = 'ONLINE'
                """,
                payload.bootId(),
                robotId,
                payload.schemaVersion(),
                receivedAt,
                receivedAt,
                receivedAt,
                payload.reason().name(),
                receivedAt,
                receivedAt);
        assertBootOwnership(robotId, payload.bootId());
        return changed == 1;
    }

    private int insertStateHistory(
            UUID robotId,
            StatePayload payload,
            String rawPayload,
            OffsetDateTime observedAt,
            OffsetDateTime receivedAt
    ) {
        StatePayload.Mission mission = payload.mission();
        StatePayload.Battery battery = payload.battery();
        StatePayload.Health health = payload.health();

        return jdbcTemplate.update("""
                INSERT INTO telemetry.robot_status_history (
                    robot_id,
                    boot_id,
                    event_kind,
                    seq,
                    robot_mode,
                    reason_code,
                    mission_status,
                    battery_valid,
                    battery_percent,
                    battery_state,
                    camera_health,
                    lidar_health,
                    gps_health,
                    battery_health,
                    motor_health,
                    storage_health,
                    observed_at,
                    received_at,
                    raw_payload
                ) VALUES (
                    ?, ?, 'STATE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, CAST(? AS JSONB)
                )
                ON CONFLICT DO NOTHING
                """,
                robotId,
                payload.bootId(),
                payload.seq(),
                payload.mode().name(),
                payload.reasonCode().name(),
                mission == null ? null : mission.state().name(),
                battery.valid(),
                battery.percent(),
                battery.state().name(),
                health.camera().name(),
                health.lidar().name(),
                health.gps().name(),
                health.battery().name(),
                health.motor().name(),
                health.storage().name(),
                observedAt,
                receivedAt,
                rawPayload);
    }

    private boolean updateCurrentState(
            UUID robotId,
            StatePayload payload,
            OffsetDateTime observedAt,
            OffsetDateTime receivedAt
    ) {
        StatePayload.Mission mission = payload.mission();
        StatePayload.Battery battery = payload.battery();
        StatePayload.Health health = payload.health();
        String overallHealth = overallHealth(health);

        int updated = jdbcTemplate.update("""
                UPDATE telemetry.robot_current_state
                SET current_boot_id = ?,
                    last_seen_seq = (
                        SELECT last_seen_seq
                        FROM telemetry.robot_boot_session
                        WHERE robot_id = ?
                          AND boot_id = ?
                    ),
                    last_received_at = CASE
                        WHEN last_received_at IS NULL OR last_received_at < ?
                            THEN ?
                        ELSE last_received_at
                    END,
                    state_schema_version = ?,
                    state_seq = ?,
                    state_observed_at = ?,
                    state_received_at = ?,
                    robot_mode = ?,
                    state_reason_code = ?,
                    mission_status = ?,
                    battery_valid = CASE
                        WHEN battery_observed_at IS NULL
                          OR ? >= battery_observed_at
                            THEN ?
                        ELSE battery_valid
                    END,
                    battery_percent = CASE
                        WHEN battery_observed_at IS NULL
                          OR ? >= battery_observed_at
                            THEN ?
                        ELSE battery_percent
                    END,
                    battery_state = CASE
                        WHEN battery_observed_at IS NULL
                          OR ? >= battery_observed_at
                            THEN ?
                        ELSE battery_state
                    END,
                    battery_source = CASE
                        WHEN battery_observed_at IS NULL
                          OR ? >= battery_observed_at
                            THEN NULL
                        ELSE battery_source
                    END,
                    battery_observed_at = CASE
                        WHEN battery_observed_at IS NULL
                          OR ? >= battery_observed_at
                            THEN ?
                        ELSE battery_observed_at
                    END,
                    camera_health = ?,
                    lidar_health = ?,
                    gps_health = ?,
                    battery_health = ?,
                    motor_health = ?,
                    storage_health = ?,
                    overall_health_status = ?,
                    updated_at = GREATEST(updated_at, ?)
                WHERE robot_id = ?
                """,
                payload.bootId(),
                robotId,
                payload.bootId(),
                receivedAt,
                receivedAt,
                payload.schemaVersion(),
                payload.seq(),
                observedAt,
                receivedAt,
                payload.mode().name(),
                payload.reasonCode().name(),
                mission == null ? null : mission.state().name(),
                observedAt,
                battery.valid(),
                observedAt,
                battery.percent(),
                observedAt,
                battery.state().name(),
                observedAt,
                observedAt,
                observedAt,
                health.camera().name(),
                health.lidar().name(),
                health.gps().name(),
                health.battery().name(),
                health.motor().name(),
                health.storage().name(),
                overallHealth,
                receivedAt,
                robotId);
        return updated == 1;
    }

    private void resetCurrentMeasurementsForBootSwitch(UUID robotId) {
        int updated = jdbcTemplate.update("""
                UPDATE telemetry.robot_current_state
                SET last_seen_seq = NULL,
                    state_schema_version = NULL,
                    state_seq = NULL,
                    state_observed_at = NULL,
                    state_received_at = NULL,
                    robot_mode = NULL,
                    state_reason_code = NULL,
                    mission_status = NULL,
                    battery_valid = NULL,
                    battery_percent = NULL,
                    battery_state = NULL,
                    battery_source = NULL,
                    battery_voltage_v = NULL,
                    battery_current_a = NULL,
                    charging = NULL,
                    battery_temperature_c = NULL,
                    estimated_runtime_sec = NULL,
                    battery_observed_at = NULL,
                    camera_health = NULL,
                    lidar_health = NULL,
                    gps_health = NULL,
                    battery_health = NULL,
                    motor_health = NULL,
                    storage_health = NULL,
                    telemetry_seq = NULL,
                    telemetry_sampled_at = NULL,
                    telemetry_received_at = NULL,
                    position_valid = NULL,
                    position = NULL,
                    heading_deg = NULL,
                    moving = NULL,
                    speed_mps = NULL,
                    jetson_temp_c = NULL,
                    overall_health_status = 'UNKNOWN'
                WHERE robot_id = ?
                """,
                robotId);
        if (updated != 1) {
            throw new IllegalStateException(
                    "Registered robot does not have a current-state row");
        }
    }

    private void updateCurrentConnectivity(
            UUID robotId,
            UUID bootId,
            String connectivityStatus,
            OffsetDateTime receivedAt
    ) {
        int updated = jdbcTemplate.update("""
                UPDATE telemetry.robot_current_state
                SET current_boot_id = ?,
                    connectivity_status = ?,
                    connection_changed_at = ?,
                    last_received_at = CASE
                        WHEN last_received_at IS NULL OR last_received_at < ?
                            THEN ?
                        ELSE last_received_at
                    END,
                    last_seen_seq = CASE
                        WHEN current_boot_id IS DISTINCT FROM ? THEN (
                            SELECT last_seen_seq
                            FROM telemetry.robot_boot_session
                            WHERE robot_id = ?
                              AND boot_id = ?
                        )
                        ELSE last_seen_seq
                    END,
                    updated_at = GREATEST(updated_at, ?)
                WHERE robot_id = ?
                """,
                bootId,
                connectivityStatus,
                receivedAt,
                receivedAt,
                receivedAt,
                bootId,
                robotId,
                bootId,
                receivedAt,
                robotId);
        if (updated != 1) {
            throw new IllegalStateException(
                    "Registered robot does not have a current-state row");
        }
    }

    private void updateCurrentLastReceived(
            UUID robotId,
            UUID bootId,
            OffsetDateTime receivedAt
    ) {
        jdbcTemplate.update("""
                UPDATE telemetry.robot_current_state
                SET last_received_at = CASE
                        WHEN last_received_at IS NULL OR last_received_at < ?
                            THEN ?
                        ELSE last_received_at
                    END,
                    updated_at = GREATEST(updated_at, ?)
                WHERE robot_id = ?
                  AND current_boot_id = ?
                """,
                receivedAt,
                receivedAt,
                receivedAt,
                robotId,
                bootId);
    }

    private void insertPresenceHistory(
            UUID robotId,
            UUID bootId,
            String status,
            String reason,
            String rawPayload,
            OffsetDateTime observedAt,
            OffsetDateTime receivedAt
    ) {
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
                ) VALUES (?, ?, 'PRESENCE', ?, ?, ?, ?, CAST(? AS JSONB))
                """,
                robotId,
                bootId,
                status,
                reason,
                observedAt,
                receivedAt,
                rawPayload);
    }

    private void closeReplacedBoot(
            UUID robotId,
            UUID bootId,
            OffsetDateTime receivedAt
    ) {
        jdbcTemplate.update("""
                UPDATE telemetry.robot_boot_session
                SET session_status = 'OFFLINE',
                    offline_received_at = ?,
                    offline_reason = 'REPLACED_BY_NEW_BOOT',
                    updated_at = GREATEST(updated_at, ?)
                WHERE robot_id = ?
                  AND boot_id = ?
                  AND session_status = 'ONLINE'
                """,
                receivedAt,
                receivedAt,
                robotId,
                bootId);
    }

    private boolean shouldUpdateState(
            CurrentState current,
            UUID incomingBootId,
            long incomingSequence,
            OffsetDateTime observedAt
    ) {
        if (current.bootId() == null) {
            return true;
        }
        if (incomingBootId.equals(current.bootId())) {
            return (current.stateSequence() == null
                    || incomingSequence > current.stateSequence())
                    && (current.stateObservedAt() == null
                    || !observedAt.isBefore(current.stateObservedAt()));
        }

        BootSession incoming = findBootSession(incomingBootId);
        BootSession existing = findBootSession(current.bootId());
        return incoming.orderAt().isAfter(existing.orderAt())
                && (current.stateObservedAt() == null
                    || observedAt.isAfter(current.stateObservedAt()));
    }

    private CurrentState lockCurrentState(UUID robotId) {
        CurrentState state = jdbcTemplate.query("""
                        SELECT
                            current_boot_id,
                            connectivity_status,
                            state_seq,
                            state_observed_at
                        FROM telemetry.robot_current_state
                        WHERE robot_id = ?
                        FOR UPDATE
                        """,
                resultSet -> resultSet.next()
                        ? new CurrentState(
                                resultSet.getObject(
                                        "current_boot_id",
                                        UUID.class),
                                resultSet.getString(
                                        "connectivity_status"),
                                resultSet.getObject(
                                        "state_seq",
                                        Long.class),
                                resultSet.getObject(
                                        "state_observed_at",
                                        OffsetDateTime.class))
                        : null,
                robotId);
        if (state == null) {
            throw new IllegalStateException(
                    "Registered robot does not have a current-state row");
        }
        return state;
    }

    private RobotReference findRobot(String mqttId) {
        RobotReference robot = jdbcTemplate.query("""
                        SELECT robot_id, mqtt_id
                        FROM robot.robot
                        WHERE mqtt_id = ?
                          AND operational_status <> 'DISABLED'
                        """,
                resultSet -> resultSet.next()
                        ? new RobotReference(
                                resultSet.getObject("robot_id", UUID.class),
                                resultSet.getString("mqtt_id"))
                        : null,
                mqttId);
        if (robot == null) {
            throw new BusinessException(ErrorCode.ROBOT_NOT_FOUND);
        }
        return robot;
    }

    private void assertBootOwnership(UUID robotId, UUID bootId) {
        UUID owner = jdbcTemplate.query("""
                        SELECT robot_id
                        FROM telemetry.robot_boot_session
                        WHERE boot_id = ?
                        """,
                resultSet -> resultSet.next()
                        ? resultSet.getObject("robot_id", UUID.class)
                        : null,
                bootId);
        if (owner != null && !owner.equals(robotId)) {
            throw new BusinessException(
                    ErrorCode.MQTT_ID_MISMATCH,
                    "boot_id is already assigned to another robot");
        }
    }

    private BootSession findBootSession(UUID bootId) {
        BootSession session = jdbcTemplate.query("""
                        SELECT
                            session_status,
                            COALESCE(robot_connected_at, first_received_at)
                                AS order_at
                        FROM telemetry.robot_boot_session
                        WHERE boot_id = ?
                        """,
                resultSet -> resultSet.next()
                        ? new BootSession(
                                resultSet.getString(
                                        "session_status"),
                                resultSet.getObject(
                                        "order_at",
                                        OffsetDateTime.class))
                        : null,
                bootId);
        if (session == null) {
            throw new IllegalStateException("Boot session does not exist");
        }
        return session;
    }

    private String overallHealth(StatePayload.Health health) {
        HealthStatus[] statuses = {
                health.camera(),
                health.lidar(),
                health.gps(),
                health.battery(),
                health.motor(),
                health.storage()
        };
        for (HealthStatus status : statuses) {
            if (status == HealthStatus.FAULT) {
                return "CRITICAL";
            }
        }
        for (HealthStatus status : statuses) {
            if (status == HealthStatus.DEGRADED) {
                return "WARNING";
            }
        }
        for (HealthStatus status : statuses) {
            if (status == HealthStatus.UNKNOWN) {
                return "UNKNOWN";
            }
        }
        return "OK";
    }

    private OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private Instant commandObservedAt(OffsetDateTime receivedAt) {
        return receivedAt.toInstant();
    }

    private record RobotReference(UUID robotId, String mqttId) {
    }

    private record CurrentState(
            UUID bootId,
            String connectivityStatus,
            Long stateSequence,
            OffsetDateTime stateObservedAt
    ) {
    }

    private record BootSession(
            String status,
            OffsetDateTime orderAt
    ) {
    }
}
