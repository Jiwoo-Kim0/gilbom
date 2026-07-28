package com.gilbom.robot.mission;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.robot.data.mqtt.v2.inbound.MissionEventPayload;
import com.gilbom.robot.event.RobotEventIngestionResult;
import com.gilbom.robot.event.RobotEventIngestionService;

@Service
public class MissionEventIngestionService {

    private final JdbcTemplate jdbcTemplate;
    private final RobotEventIngestionService eventIngestionService;
    private final ObjectMapper objectMapper;

    public MissionEventIngestionService(
            JdbcTemplate jdbcTemplate,
            RobotEventIngestionService eventIngestionService,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.eventIngestionService = eventIngestionService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RobotEventIngestionResult ingest(
            MissionEventPayload payload,
            String rawPayload,
            Instant receivedAt
    ) {
        RobotEventIngestionResult event = eventIngestionService.ingest(
                payload,
                payload.eventType(),
                rawPayload,
                receivedAt);
        if (!event.inserted()) {
            return event;
        }

        MissionState current = lockMission(
                event.missionId(),
                event.robotId());
        String targetStatus = targetStatus(payload.eventType());
        if (current.status().equals(targetStatus)) {
            throw new BusinessException(ErrorCode.MISSION_STATE_CONFLICT);
        }

        try {
            updateMission(
                    event.missionId(),
                    event.robotId(),
                    payload,
                    targetStatus);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(
                    ErrorCode.MISSION_STATE_CONFLICT,
                    exception);
        }
        appendHistory(
                event.missionId(),
                event.eventId(),
                current.status(),
                targetStatus,
                payload,
                receivedAt);
        return event;
    }

    private MissionState lockMission(UUID missionId, UUID robotId) {
        MissionState state = jdbcTemplate.query("""
                        SELECT status, started_at
                        FROM robot.mission
                        WHERE mission_id = ?
                          AND robot_id = ?
                        FOR UPDATE
                        """,
                resultSet -> resultSet.next()
                        ? new MissionState(
                                resultSet.getString("status"),
                                resultSet.getObject(
                                        "started_at",
                                        OffsetDateTime.class))
                        : null,
                missionId,
                robotId);
        if (state == null) {
            throw new BusinessException(ErrorCode.MISSION_NOT_FOUND);
        }
        return state;
    }

    private void updateMission(
            UUID missionId,
            UUID robotId,
            MissionEventPayload payload,
            String targetStatus
    ) {
        boolean started = payload.eventType()
                == MissionEventPayload.EventType.MISSION_STARTED;
        boolean terminal = payload.result() != null;
        MissionEventPayload.Result result = payload.result();
        int updated = jdbcTemplate.update("""
                UPDATE robot.mission
                SET status = ?,
                    started_at = CASE
                        WHEN ? THEN COALESCE(started_at, ?)
                        ELSE started_at
                    END,
                    ended_at = CASE
                        WHEN ? THEN ?
                        ELSE ended_at
                    END,
                    progress_percent = CASE
                        WHEN ? = 'COMPLETED' THEN 100
                        ELSE progress_percent
                    END,
                    completion_reason = ?,
                    result_duration_sec = ?,
                    result_incident_count = ?,
                    result_normal_detection_count = ?,
                    result_payload = CAST(? AS JSONB),
                    updated_at = NOW()
                WHERE mission_id = ?
                  AND robot_id = ?
                """,
                targetStatus,
                started,
                utc(payload.occurredAt()),
                terminal,
                terminal ? utc(payload.occurredAt()) : null,
                targetStatus,
                result == null ? null : result.completionReason(),
                result == null ? null : result.durationSec(),
                result == null ? null : result.incidentCount(),
                result == null ? null : result.normalDetectionCount(),
                result == null ? null : json(result),
                missionId,
                robotId);
        if (updated != 1) {
            throw new BusinessException(ErrorCode.MISSION_NOT_FOUND);
        }
    }

    private void appendHistory(
            UUID missionId,
            UUID eventId,
            String beforeStatus,
            String afterStatus,
            MissionEventPayload payload,
            Instant receivedAt
    ) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO robot.mission_status_history (
                    mission_id,
                    source_event_id,
                    transition_source,
                    before_status,
                    after_status,
                    transition_reason,
                    result_payload,
                    occurred_at,
                    received_at
                ) VALUES (
                    ?,
                    ?,
                    'MQTT',
                    ?,
                    ?,
                    ?,
                    CAST(? AS JSONB),
                    ?,
                    ?
                )
                """,
                missionId,
                eventId,
                beforeStatus,
                afterStatus,
                payload.result() == null
                        ? null
                        : payload.result().completionReason(),
                payload.result() == null
                        ? null
                        : json(payload.result()),
                utc(payload.occurredAt()),
                utc(receivedAt));
        if (inserted != 1) {
            throw new IllegalStateException(
                    "Mission status history was not persisted");
        }
    }

    private String targetStatus(MissionEventPayload.EventType eventType) {
        return switch (eventType) {
            case MISSION_STARTED, MISSION_RESUMED -> "RUNNING";
            case MISSION_PAUSED -> "PAUSED";
            case MISSION_COMPLETED -> "COMPLETED";
            case MISSION_CANCELLED -> "CANCELLED";
            case MISSION_FAILED -> "FAILED";
        };
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    ErrorCode.COMMON_INTERNAL_ERROR,
                    exception);
        }
    }

    private OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record MissionState(
            String status,
            OffsetDateTime startedAt
    ) {
    }
}
