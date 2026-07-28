package com.gilbom.robot.event;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.robot.data.mqtt.v2.common.MqttEventPayload;

@Service
public class RobotEventIngestionService {

    private final JdbcTemplate jdbcTemplate;

    public RobotEventIngestionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public RobotEventIngestionResult ingest(
            MqttEventPayload payload,
            Enum<?> eventType,
            String rawPayload,
            Instant receivedAt
    ) {
        UUID robotId = findRobotId(payload.mqttId());
        UUID missionId = findMissionId(robotId, payload.missionId());
        OffsetDateTime occurredAt = utc(payload.occurredAt());

        ensureBootSession(
                robotId,
                payload.bootId(),
                payload.schemaVersion(),
                utc(receivedAt));

        ExistingEvent existing = findByEventId(payload.eventId());
        if (existing != null) {
            requireSameEvent(
                    existing,
                    payload,
                    eventType,
                    robotId,
                    missionId,
                    occurredAt);
            return new RobotEventIngestionResult(
                    payload.eventId(),
                    robotId,
                    missionId,
                    false);
        }

        if (sequenceExists(robotId, payload.bootId(), payload.seq())) {
            throw eventConflict();
        }

        int inserted;
        try {
            inserted = jdbcTemplate.update("""
                    INSERT INTO telemetry.robot_event (
                        event_id,
                        schema_version,
                        robot_id,
                        boot_id,
                        seq,
                        mission_id,
                        event_type,
                        source_component,
                        occurred_at,
                        sent_at,
                        received_at,
                        raw_payload
                    ) VALUES (
                        ?,
                        ?,
                        ?,
                        ?,
                        ?,
                        ?,
                        ?,
                        ?,
                        ?,
                        ?,
                        ?,
                        CAST(? AS JSONB)
                    )
                    ON CONFLICT (event_id) DO NOTHING
                    """,
                    payload.eventId(),
                    payload.schemaVersion(),
                    robotId,
                    payload.bootId(),
                    payload.seq(),
                    missionId,
                    eventType.name(),
                    payload.sourceComponent().value(),
                    occurredAt,
                    utc(payload.sentAt()),
                    utc(receivedAt),
                    rawPayload);
        } catch (DuplicateKeyException exception) {
            // event_id 외 UNIQUE, 특히 (robot_id, boot_id, seq) 충돌이다.
            // PostgreSQL statement 오류 뒤에는 현재 Transaction에서 재조회하지 않는다.
            throw eventConflict();
        }
        if (inserted == 0) {
            ExistingEvent raced = findByEventId(payload.eventId());
            if (raced == null) {
                throw new IllegalStateException(
                        "Conflicting Event disappeared");
            }
            requireSameEvent(
                    raced,
                    payload,
                    eventType,
                    robotId,
                    missionId,
                    occurredAt);
            return new RobotEventIngestionResult(
                    payload.eventId(),
                    robotId,
                    missionId,
                    false);
        }

        touchBootSession(
                robotId,
                payload.bootId(),
                payload.seq(),
                utc(receivedAt));
        return new RobotEventIngestionResult(
                payload.eventId(),
                robotId,
                missionId,
                true);
    }

    private UUID findRobotId(String mqttId) {
        UUID robotId = jdbcTemplate.query("""
                        SELECT robot_id
                        FROM robot.robot
                        WHERE mqtt_id = ?
                          AND operational_status <> 'DISABLED'
                        """,
                resultSet -> resultSet.next()
                        ? resultSet.getObject("robot_id", UUID.class)
                        : null,
                mqttId);
        if (robotId == null) {
            throw new BusinessException(ErrorCode.ROBOT_NOT_FOUND);
        }
        return robotId;
    }

    private UUID findMissionId(UUID robotId, String missionCode) {
        if (missionCode == null) {
            return null;
        }
        UUID missionId = jdbcTemplate.query("""
                        SELECT mission_id
                        FROM robot.mission
                        WHERE mission_code = ?
                          AND robot_id = ?
                        """,
                resultSet -> resultSet.next()
                        ? resultSet.getObject("mission_id", UUID.class)
                        : null,
                missionCode,
                robotId);
        if (missionId == null) {
            throw new BusinessException(ErrorCode.MISSION_NOT_FOUND);
        }
        return missionId;
    }

    private void ensureBootSession(
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

        UUID owner = jdbcTemplate.query("""
                        SELECT robot_id
                        FROM telemetry.robot_boot_session
                        WHERE boot_id = ?
                        """,
                resultSet -> resultSet.next()
                        ? resultSet.getObject("robot_id", UUID.class)
                        : null,
                bootId);
        if (owner == null || !owner.equals(robotId)) {
            throw new BusinessException(
                    ErrorCode.MQTT_ID_MISMATCH,
                    "boot_id is already assigned to another robot");
        }
    }

    private void touchBootSession(
            UUID robotId,
            UUID bootId,
            long sequence,
            OffsetDateTime receivedAt
    ) {
        int updated = jdbcTemplate.update("""
                UPDATE telemetry.robot_boot_session
                SET last_received_at = GREATEST(last_received_at, ?),
                    last_seen_seq = GREATEST(
                        COALESCE(last_seen_seq, 0),
                        ?
                    ),
                    updated_at = GREATEST(updated_at, ?)
                WHERE robot_id = ?
                  AND boot_id = ?
                """,
                receivedAt,
                sequence,
                receivedAt,
                robotId,
                bootId);
        if (updated != 1) {
            throw new IllegalStateException(
                    "Boot session could not be updated");
        }
    }

    private ExistingEvent findByEventId(UUID eventId) {
        return jdbcTemplate.query("""
                        SELECT
                            schema_version,
                            robot_id,
                            boot_id,
                            seq,
                            mission_id,
                            event_type,
                            source_component,
                            occurred_at
                        FROM telemetry.robot_event
                        WHERE event_id = ?
                        """,
                resultSet -> {
                    if (!resultSet.next()) {
                        return null;
                    }
                    return new ExistingEvent(
                            resultSet.getString("schema_version"),
                            resultSet.getObject("robot_id", UUID.class),
                            resultSet.getObject("boot_id", UUID.class),
                            resultSet.getLong("seq"),
                            resultSet.getObject("mission_id", UUID.class),
                            resultSet.getString("event_type"),
                            resultSet.getString("source_component"),
                            resultSet.getObject(
                                    "occurred_at",
                                    OffsetDateTime.class));
                },
                eventId);
    }

    private boolean sequenceExists(
            UUID robotId,
            UUID bootId,
            long sequence
    ) {
        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM telemetry.robot_event
                    WHERE robot_id = ?
                      AND boot_id = ?
                      AND seq = ?
                )
                """,
                Boolean.class,
                robotId,
                bootId,
                sequence);
        return Boolean.TRUE.equals(exists);
    }

    private void requireSameEvent(
            ExistingEvent existing,
            MqttEventPayload payload,
            Enum<?> eventType,
            UUID robotId,
            UUID missionId,
            OffsetDateTime occurredAt
    ) {
        boolean same = existing.schemaVersion().equals(
                payload.schemaVersion())
                && existing.robotId().equals(robotId)
                && existing.bootId().equals(payload.bootId())
                && existing.sequence() == payload.seq()
                && java.util.Objects.equals(
                        existing.missionId(),
                        missionId)
                && existing.eventType().equals(eventType.name())
                && existing.sourceComponent().equals(
                        payload.sourceComponent().value())
                && existing.occurredAt().toInstant().equals(
                        occurredAt.toInstant());
        if (!same) {
            throw eventConflict();
        }
    }

    private BusinessException eventConflict() {
        return new BusinessException(ErrorCode.MQTT_EVENT_CONFLICT);
    }

    private OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record ExistingEvent(
            String schemaVersion,
            UUID robotId,
            UUID bootId,
            long sequence,
            UUID missionId,
            String eventType,
            String sourceComponent,
            OffsetDateTime occurredAt
    ) {
    }
}
