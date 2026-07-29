package com.gilbom.robot.mqtt.replay;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.robot.mqtt.topic.MqttTopicType;

import io.micrometer.core.instrument.MeterRegistry;

@Service
public class MqttReplayProtectionService {

    static final String REJECTION_METRIC =
            "gilbom.mqtt.inbound.rejected";

    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;

    public MqttReplayProtectionService(
            JdbcTemplate jdbcTemplate,
            MeterRegistry meterRegistry
    ) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
    }

    public ClaimResult claim(
            MqttTopicType topicType,
            SequenceKind sequenceKind,
            UUID robotId,
            UUID bootId,
            long sequence,
            UUID messageKey,
            String schemaVersion,
            String rawPayload,
            Instant serverReceivedAt
    ) {
        Objects.requireNonNull(topicType, "topicType must not be null");
        Objects.requireNonNull(
                sequenceKind,
                "sequenceKind must not be null");
        Objects.requireNonNull(robotId, "robotId must not be null");
        Objects.requireNonNull(bootId, "bootId must not be null");
        Objects.requireNonNull(
                schemaVersion,
                "schemaVersion must not be null");
        Objects.requireNonNull(
                rawPayload,
                "rawPayload must not be null");
        Objects.requireNonNull(
                serverReceivedAt,
                "serverReceivedAt must not be null");
        if (sequenceKind != SequenceKind.STATE && messageKey == null) {
            throw new IllegalArgumentException(
                    "messageKey is required for " + sequenceKind);
        }

        UUID currentBootId = lockCurrentBoot(robotId);
        List<ExistingClaim> sequenceClaims = findSequenceClaims(
                robotId,
                bootId,
                sequence,
                rawPayload);
        if (!sequenceClaims.isEmpty()) {
            return resolveExistingClaim(
                    topicType,
                    sequenceKind,
                    robotId,
                    bootId,
                    sequence,
                    messageKey,
                    sequenceClaims);
        }

        ExistingClaim identityClaim = findIdentityClaim(
                sequenceKind,
                messageKey,
                rawPayload);
        if (identityClaim != null) {
            reject(
                    topicType,
                    "sequence_conflict",
                    ErrorCode.MQTT_SEQUENCE_CONFLICT);
        }

        OffsetDateTime receivedAt = utc(serverReceivedAt);
        BootSession session = lockBootSession(bootId);
        boolean newSession = false;
        if (session == null) {
            if (sequence != 1L) {
                reject(
                        topicType,
                        "new_boot_sequence",
                        ErrorCode.MQTT_REPLAY_REJECTED);
            }
            insertBootSession(
                    robotId,
                    bootId,
                    schemaVersion,
                    receivedAt);
            session = lockBootSession(bootId);
            newSession = true;
        }

        if (session == null) {
            throw new IllegalStateException(
                    "Boot session could not be created");
        }
        if (!session.robotId().equals(robotId)) {
            throw new BusinessException(
                    ErrorCode.MQTT_ID_MISMATCH,
                    "boot_id is already assigned to another robot");
        }
        if (!"ONLINE".equals(session.status())) {
            reject(
                    topicType,
                    "closed_boot",
                    ErrorCode.MQTT_REPLAY_REJECTED);
        }
        if (session.lastSeenSequence() == null) {
            if (sequence != 1L) {
                reject(
                        topicType,
                        "new_boot_sequence",
                        ErrorCode.MQTT_REPLAY_REJECTED);
            }
        } else if (sequence <= session.lastSeenSequence()) {
            reject(
                    topicType,
                    "non_increasing_sequence",
                    ErrorCode.MQTT_REPLAY_REJECTED);
        }

        if (currentBootId != null
                && !currentBootId.equals(bootId)) {
            BootSession currentSession =
                    lockBootSession(currentBootId);
            if (!newSession
                    && currentSession != null
                    && !session.orderAt().isAfter(
                            currentSession.orderAt())) {
                reject(
                        topicType,
                        "previous_boot",
                        ErrorCode.MQTT_REPLAY_REJECTED);
            }
            closeReplacedBoot(
                    robotId,
                    currentBootId,
                    receivedAt);
        }

        updateBootSequence(
                robotId,
                bootId,
                sequence,
                receivedAt);
        updateCurrentSequence(
                robotId,
                bootId,
                sequence,
                receivedAt,
                currentBootId == null
                        || !currentBootId.equals(bootId));
        return ClaimResult.ACCEPTED;
    }

    private ClaimResult resolveExistingClaim(
            MqttTopicType topicType,
            SequenceKind sequenceKind,
            UUID robotId,
            UUID bootId,
            long sequence,
            UUID messageKey,
            List<ExistingClaim> claims
    ) {
        if (claims.size() != 1) {
            reject(
                    topicType,
                    "sequence_conflict",
                    ErrorCode.MQTT_SEQUENCE_CONFLICT);
        }

        ExistingClaim existing = claims.getFirst();
        boolean sameCoordinates =
                existing.robotId().equals(robotId)
                && existing.bootId().equals(bootId)
                && existing.sequence() == sequence;
        boolean sameIdentity = switch (sequenceKind) {
            case STATE -> existing.kind() == SequenceKind.STATE
                    && existing.payloadMatches();
            case TELEMETRY -> existing.kind() == SequenceKind.TELEMETRY
                    && messageKey.toString().equals(
                            existing.messageKey())
                    && existing.payloadMatches();
            case EVENT -> existing.kind() == SequenceKind.EVENT
                    && messageKey.toString().equals(
                            existing.messageKey());
        };
        if (sameCoordinates && sameIdentity) {
            return ClaimResult.DUPLICATE;
        }

        reject(
                topicType,
                "sequence_conflict",
                ErrorCode.MQTT_SEQUENCE_CONFLICT);
        throw new IllegalStateException("unreachable");
    }

    private List<ExistingClaim> findSequenceClaims(
            UUID robotId,
            UUID bootId,
            long sequence,
            String rawPayload
    ) {
        return jdbcTemplate.query("""
                SELECT
                    claim_kind,
                    claim_robot_id,
                    claim_boot_id,
                    claim_seq,
                    message_key,
                    payload_matches
                FROM (
                    SELECT
                        'TELEMETRY' AS claim_kind,
                        robot_id AS claim_robot_id,
                        boot_id AS claim_boot_id,
                        seq AS claim_seq,
                        message_id::TEXT AS message_key,
                        raw_payload = CAST(? AS JSONB)
                            AS payload_matches
                    FROM telemetry.robot_telemetry
                    WHERE robot_id = ?
                      AND boot_id = ?
                      AND seq = ?

                    UNION ALL

                    SELECT
                        'STATE',
                        robot_id,
                        boot_id,
                        seq,
                        NULL,
                        raw_payload = CAST(? AS JSONB)
                    FROM telemetry.robot_status_history
                    WHERE robot_id = ?
                      AND boot_id = ?
                      AND seq = ?
                      AND event_kind = 'STATE'

                    UNION ALL

                    SELECT
                        'EVENT',
                        robot_id,
                        boot_id,
                        seq,
                        event_id::TEXT,
                        raw_payload = CAST(? AS JSONB)
                    FROM telemetry.robot_event
                    WHERE robot_id = ?
                      AND boot_id = ?
                      AND seq = ?
                ) claims
                """,
                (resultSet, rowNumber) -> new ExistingClaim(
                        SequenceKind.valueOf(
                                resultSet.getString("claim_kind")),
                        resultSet.getObject(
                                "claim_robot_id",
                                UUID.class),
                        resultSet.getObject(
                                "claim_boot_id",
                                UUID.class),
                        resultSet.getLong("claim_seq"),
                        resultSet.getString("message_key"),
                        resultSet.getBoolean("payload_matches")),
                rawPayload,
                robotId,
                bootId,
                sequence,
                rawPayload,
                robotId,
                bootId,
                sequence,
                rawPayload,
                robotId,
                bootId,
                sequence);
    }

    private ExistingClaim findIdentityClaim(
            SequenceKind sequenceKind,
            UUID messageKey,
            String rawPayload
    ) {
        if (sequenceKind == SequenceKind.STATE) {
            return null;
        }
        String table = sequenceKind == SequenceKind.TELEMETRY
                ? "telemetry.robot_telemetry"
                : "telemetry.robot_event";
        String keyColumn = sequenceKind == SequenceKind.TELEMETRY
                ? "message_id"
                : "event_id";
        return jdbcTemplate.query("""
                        SELECT
                            robot_id,
                            boot_id,
                            seq,
                            raw_payload = CAST(? AS JSONB)
                                AS payload_matches
                        FROM %s
                        WHERE %s = ?
                        """.formatted(table, keyColumn),
                resultSet -> resultSet.next()
                        ? new ExistingClaim(
                                sequenceKind,
                                resultSet.getObject(
                                        "robot_id",
                                        UUID.class),
                                resultSet.getObject(
                                        "boot_id",
                                        UUID.class),
                                resultSet.getLong("seq"),
                                messageKey.toString(),
                                resultSet.getBoolean(
                                        "payload_matches"))
                        : null,
                rawPayload,
                messageKey);
    }

    private UUID lockCurrentBoot(UUID robotId) {
        CurrentBoot current = jdbcTemplate.query("""
                        SELECT current_boot_id
                        FROM telemetry.robot_current_state
                        WHERE robot_id = ?
                        FOR UPDATE
                        """,
                resultSet -> resultSet.next()
                        ? new CurrentBoot(resultSet.getObject(
                                "current_boot_id",
                                UUID.class))
                        : null,
                robotId);
        if (current == null) {
            throw new IllegalStateException(
                    "Registered robot does not have a current-state row");
        }
        return current.bootId();
    }

    private BootSession lockBootSession(UUID bootId) {
        return jdbcTemplate.query("""
                        SELECT
                            robot_id,
                            session_status,
                            last_seen_seq,
                            COALESCE(
                                robot_connected_at,
                                first_received_at
                            ) AS order_at
                        FROM telemetry.robot_boot_session
                        WHERE boot_id = ?
                        FOR UPDATE
                        """,
                resultSet -> resultSet.next()
                        ? new BootSession(
                                resultSet.getObject(
                                        "robot_id",
                                        UUID.class),
                                resultSet.getString(
                                        "session_status"),
                                resultSet.getObject(
                                        "last_seen_seq",
                                        Long.class),
                                resultSet.getObject(
                                        "order_at",
                                        OffsetDateTime.class))
                        : null,
                bootId);
    }

    private void insertBootSession(
            UUID robotId,
            UUID bootId,
            String schemaVersion,
            OffsetDateTime receivedAt
    ) {
        jdbcTemplate.update("""
                INSERT INTO telemetry.robot_boot_session (
                    boot_id,
                    robot_id,
                    schema_version,
                    session_status,
                    first_received_at,
                    last_received_at,
                    created_at,
                    updated_at
                ) VALUES (?, ?, ?, 'ONLINE', ?, ?, ?, ?)
                ON CONFLICT (boot_id) DO NOTHING
                """,
                bootId,
                robotId,
                schemaVersion,
                receivedAt,
                receivedAt,
                receivedAt,
                receivedAt);
    }

    private void updateBootSequence(
            UUID robotId,
            UUID bootId,
            long sequence,
            OffsetDateTime receivedAt
    ) {
        int updated = jdbcTemplate.update("""
                UPDATE telemetry.robot_boot_session
                SET last_received_at = GREATEST(last_received_at, ?),
                    last_seen_seq = ?,
                    updated_at = GREATEST(updated_at, ?)
                WHERE robot_id = ?
                  AND boot_id = ?
                  AND session_status = 'ONLINE'
                """,
                receivedAt,
                sequence,
                receivedAt,
                robotId,
                bootId);
        if (updated != 1) {
            throw new IllegalStateException(
                    "Boot sequence could not be updated");
        }
    }

    private void updateCurrentSequence(
            UUID robotId,
            UUID bootId,
            long sequence,
            OffsetDateTime receivedAt,
            boolean bootChanged
    ) {
        if (bootChanged) {
            resetCurrentForBoot(
                    robotId,
                    bootId,
                    sequence,
                    receivedAt);
            return;
        }
        int updated = jdbcTemplate.update("""
                UPDATE telemetry.robot_current_state
                SET last_seen_seq = ?,
                    last_received_at = CASE
                        WHEN last_received_at IS NULL
                          OR last_received_at < ?
                            THEN ?
                        ELSE last_received_at
                    END,
                    updated_at = GREATEST(updated_at, ?)
                WHERE robot_id = ?
                """,
                sequence,
                receivedAt,
                receivedAt,
                receivedAt,
                robotId);
        if (updated != 1) {
            throw new IllegalStateException(
                    "Current sequence could not be updated");
        }
    }

    private void resetCurrentForBoot(
            UUID robotId,
            UUID bootId,
            long sequence,
            OffsetDateTime receivedAt
    ) {
        int updated = jdbcTemplate.update("""
                UPDATE telemetry.robot_current_state
                SET current_boot_id = ?,
                    last_seen_seq = ?,
                    last_received_at = ?,
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
                    overall_health_status = 'UNKNOWN',
                    updated_at = GREATEST(updated_at, ?)
                WHERE robot_id = ?
                """,
                bootId,
                sequence,
                receivedAt,
                receivedAt,
                robotId);
        if (updated != 1) {
            throw new IllegalStateException(
                    "Current boot could not be switched");
        }
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

    private void reject(
            MqttTopicType topicType,
            String reason,
            ErrorCode errorCode
    ) {
        meterRegistry.counter(
                REJECTION_METRIC,
                "topic",
                topicType.name(),
                "reason",
                reason).increment();
        throw new BusinessException(errorCode);
    }

    private OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public enum ClaimResult {
        ACCEPTED,
        DUPLICATE
    }

    public enum SequenceKind {
        STATE,
        TELEMETRY,
        EVENT
    }

    private record CurrentBoot(UUID bootId) {
    }

    private record BootSession(
            UUID robotId,
            String status,
            Long lastSeenSequence,
            OffsetDateTime orderAt
    ) {
    }

    private record ExistingClaim(
            SequenceKind kind,
            UUID robotId,
            UUID bootId,
            long sequence,
            String messageKey,
            boolean payloadMatches
    ) {
    }
}
