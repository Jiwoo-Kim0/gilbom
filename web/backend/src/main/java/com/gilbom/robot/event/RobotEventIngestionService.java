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
import com.gilbom.robot.data.mqtt.v2.inbound.BatteryEventPayload;
import com.gilbom.robot.data.mqtt.v2.inbound.IncidentEventPayload;
import com.gilbom.robot.data.mqtt.v2.inbound.MissionEventPayload;
import com.gilbom.robot.data.mqtt.v2.inbound.SafetyEventPayload;
import com.gilbom.robot.mqtt.replay.MqttReplayProtectionService;
import com.gilbom.robot.mqtt.replay.MqttReplayProtectionService.ClaimResult;
import com.gilbom.robot.mqtt.replay.MqttReplayProtectionService.SequenceKind;
import com.gilbom.robot.mqtt.topic.MqttTopicType;

@Service
public class RobotEventIngestionService {

    private final JdbcTemplate jdbcTemplate;
    private final MqttReplayProtectionService replayProtectionService;

    public RobotEventIngestionService(
            JdbcTemplate jdbcTemplate,
            MqttReplayProtectionService replayProtectionService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.replayProtectionService = replayProtectionService;
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

        ClaimResult claim = replayProtectionService.claim(
                eventTopic(payload),
                SequenceKind.EVENT,
                robotId,
                payload.bootId(),
                payload.seq(),
                payload.eventId(),
                payload.schemaVersion(),
                rawPayload,
                receivedAt);
        if (claim == ClaimResult.DUPLICATE) {
            ExistingEvent duplicate = findByEventId(payload.eventId());
            if (duplicate == null) {
                throw new IllegalStateException(
                        "Duplicate Event could not be found");
            }
            requireSameEvent(
                    duplicate,
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
            throw new BusinessException(
                    ErrorCode.MQTT_SEQUENCE_CONFLICT,
                    exception);
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
                          AND deleted_at IS NULL
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

    private MqttTopicType eventTopic(MqttEventPayload payload) {
        if (payload instanceof IncidentEventPayload) {
            return MqttTopicType.INCIDENT_EVENT;
        }
        if (payload instanceof BatteryEventPayload) {
            return MqttTopicType.BATTERY_EVENT;
        }
        if (payload instanceof SafetyEventPayload) {
            return MqttTopicType.SAFETY_EVENT;
        }
        if (payload instanceof MissionEventPayload) {
            return MqttTopicType.MISSION_EVENT;
        }
        throw new IllegalArgumentException(
                "Unsupported MQTT Event payload: "
                        + payload.getClass().getName());
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
