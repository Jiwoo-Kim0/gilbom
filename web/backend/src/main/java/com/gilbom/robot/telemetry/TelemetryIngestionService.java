package com.gilbom.robot.telemetry;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.robot.data.application.command.TelemetryIngestionCommand;
import com.gilbom.robot.data.application.result.TelemetryIngestionResult;
import com.gilbom.robot.data.mqtt.v2.inbound.TelemetryPayload;
import com.gilbom.robot.mission.MissionProgressService;
import com.gilbom.robot.mqtt.replay.MqttReplayProtectionService;
import com.gilbom.robot.mqtt.replay.MqttReplayProtectionService.ClaimResult;
import com.gilbom.robot.mqtt.replay.MqttReplayProtectionService.SequenceKind;
import com.gilbom.robot.mqtt.topic.MqttTopicType;
import com.gilbom.robot.realtime.RobotRealtimeEvent;

@Service
public class TelemetryIngestionService {

    private final JdbcTemplate jdbcTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final MissionProgressService missionProgressService;
    private final MqttReplayProtectionService replayProtectionService;

    public TelemetryIngestionService(
            JdbcTemplate jdbcTemplate,
            ApplicationEventPublisher eventPublisher,
            MissionProgressService missionProgressService,
            MqttReplayProtectionService replayProtectionService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.eventPublisher = eventPublisher;
        this.missionProgressService = missionProgressService;
        this.replayProtectionService = replayProtectionService;
    }

    @Transactional
    public TelemetryIngestionResult ingest(TelemetryIngestionCommand command) {
        TelemetryPayload payload = command.payload();
        UUID robotId = findRobotId(payload.mqttId());
        UUID missionId = findMissionId(
                robotId,
                payload.missionId());
        OffsetDateTime sampledAt = utc(payload.sampledAt());
        OffsetDateTime receivedAt = utc(command.receivedAt());

        lockCurrentState(robotId);
        ClaimResult claim = replayProtectionService.claim(
                MqttTopicType.TELEMETRY,
                SequenceKind.TELEMETRY,
                robotId,
                payload.bootId(),
                payload.seq(),
                payload.messageId(),
                payload.schemaVersion(),
                command.rawPayload(),
                command.receivedAt());
        if (claim == ClaimResult.DUPLICATE) {
            return new TelemetryIngestionResult(false, false);
        }
        int inserted = insertHistory(
                robotId,
                payload,
                missionId,
                command.rawPayload(),
                sampledAt,
                receivedAt);

        if (inserted == 0) {
            throw new BusinessException(
                    ErrorCode.MQTT_SEQUENCE_CONFLICT);
        }

        updateMissionProgress(
                missionId,
                payload,
                sampledAt);
        boolean currentStateUpdated = updateCurrentState(
                robotId,
                payload,
                sampledAt,
                receivedAt);
        if (!currentStateUpdated) {
            updateLastReceived(
                    robotId,
                    payload.bootId(),
                    receivedAt);
        }
        if (currentStateUpdated) {
            eventPublisher.publishEvent(
                    RobotRealtimeEvent.telemetryUpdated(
                            robotId,
                            payload.mqttId(),
                            payload.sampledAt()));
        }
        return new TelemetryIngestionResult(true, currentStateUpdated);
    }

    private void updateMissionProgress(
            UUID missionId,
            TelemetryPayload payload,
            OffsetDateTime sampledAt
    ) {
        TelemetryPayload.Mission mission = payload.mission();
        if (missionId == null
                || mission == null) {
            return;
        }
        missionProgressService.refresh(
                missionId,
                payload.messageId(),
                sampledAt,
                mission.elapsedSec());
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

    private void lockCurrentState(UUID robotId) {
    	//특정 로봇의 현재 상태 행을 DB에서 잠그는 함수 (해당 Transaction의 lock을 보장)
        boolean found = !jdbcTemplate.queryForList("""
                        SELECT TRUE
                        FROM telemetry.robot_current_state
                        WHERE robot_id = ?
                        FOR UPDATE
                        """,
                Boolean.class,
                robotId)
        		// FOR UPDAT는 선택된 행을 잠굽니다.
                .isEmpty();
        if (!found) {
            throw new IllegalStateException(
                    "Registered robot does not have a current-state row");
        }
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

    private int insertHistory(
            UUID robotId,
            TelemetryPayload payload,
            UUID missionId,
            String rawPayload,
            OffsetDateTime sampledAt,
            OffsetDateTime receivedAt
    ) {
        TelemetryPayload.Position position = payload.position();
        TelemetryPayload.Motion motion = payload.motion();
        TelemetryPayload.Battery battery = payload.battery();
        TelemetryPayload.Mission mission = payload.mission();

        return jdbcTemplate.update("""
                INSERT INTO telemetry.robot_telemetry (
                    message_id,
                    schema_version,
                    robot_id,
                    boot_id,
                    seq,
                    sampled_at,
                    received_at,
                    position_valid,
                    position,
                    heading_deg,
                    moving,
                    speed_mps,
                    battery_valid,
                    battery_percent,
                    battery_state,
                    battery_source,
                    battery_voltage_v,
                    battery_current_a,
                    charging,
                    battery_temperature_c,
                    estimated_runtime_sec,
                    jetson_temp_c,
                    mission_state,
                    mission_elapsed_sec,
                    mission_incident_count,
                    mission_id,
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
                    CASE
                        WHEN ? THEN ST_SetSRID(
                            ST_MakePoint(
                                CAST(? AS DOUBLE PRECISION),
                                CAST(? AS DOUBLE PRECISION)
                            ),
                            4326
                        )
                        ELSE NULL
                    END,
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
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    CAST(? AS JSONB)
                )
                ON CONFLICT DO NOTHING
                """,
                payload.messageId(),
                payload.schemaVersion(),
                robotId,
                payload.bootId(),
                payload.seq(),
                sampledAt,
                receivedAt,
                position.valid(),
                position.valid(),
                position.lon(),
                position.lat(),
                position.headingDeg(),
                motion.moving(),
                motion.speedMps(),
                battery.valid(),
                battery.percent(),
                battery.state().name(),
                enumName(battery.source()),
                battery.voltageV(),
                battery.currentA(),
                battery.charging(),
                battery.batteryTemperatureC(),
                battery.estimatedRuntimeSec(),
                payload.system().jetsonTempC(),
                mission == null ? null : mission.state().name(),
                mission == null ? null : mission.elapsedSec(),
                mission == null ? null : mission.incidentCount(),
                missionId,
                rawPayload);
    }

    private void updateLastReceived(
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
                    updated_at = CASE
                        WHEN updated_at < ? THEN ?
                        ELSE updated_at
                    END
                WHERE robot_id = ?
                  AND current_boot_id = ?
                """,
                receivedAt,
                receivedAt,
                receivedAt,
                receivedAt,
                robotId,
                bootId);
    }

    private boolean updateCurrentState(
            UUID robotId,
            TelemetryPayload payload,
            OffsetDateTime sampledAt,
            OffsetDateTime receivedAt
    ) {
        TelemetryPayload.Position position = payload.position();
        TelemetryPayload.Motion motion = payload.motion();
        TelemetryPayload.Battery battery = payload.battery();
        TelemetryPayload.Mission mission = payload.mission();

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
                    mission_status = COALESCE(?, mission_status),
                    battery_valid = ?,
                    battery_percent = ?,
                    battery_state = ?,
                    battery_source = ?,
                    battery_voltage_v = ?,
                    battery_current_a = ?,
                    charging = ?,
                    battery_temperature_c = ?,
                    estimated_runtime_sec = ?,
                    battery_observed_at = ?,
                    telemetry_seq = ?,
                    telemetry_sampled_at = ?,
                    telemetry_received_at = ?,
                    position_valid = ?,
                    position = CASE
                        WHEN ? THEN ST_SetSRID(
                            ST_MakePoint(
                                CAST(? AS DOUBLE PRECISION),
                                CAST(? AS DOUBLE PRECISION)
                            ),
                            4326
                        )
                        ELSE position
                    END,
                    heading_deg = CASE
                        WHEN ? THEN ?
                        ELSE heading_deg
                    END,
                    moving = ?,
                    speed_mps = ?,
                    jetson_temp_c = ?,
                    updated_at = CASE
                        WHEN updated_at < ? THEN ?
                        ELSE updated_at
                    END
                WHERE robot_id = ?
                  AND (
                      current_boot_id IS NULL
                      OR (
                          current_boot_id = ?
                          AND (
                              telemetry_seq IS NULL
                              OR (
                                  ? > telemetry_seq
                                  AND (
                                      telemetry_sampled_at IS NULL
                                      OR ? >= telemetry_sampled_at
                                  )
                              )
                          )
                      )
                      OR (
                          current_boot_id IS DISTINCT FROM ?
                          AND (
                              SELECT incoming.first_received_at
                              FROM telemetry.robot_boot_session incoming
                              WHERE incoming.robot_id = ?
                                AND incoming.boot_id = ?
                          ) > (
                              SELECT current_session.first_received_at
                              FROM telemetry.robot_boot_session current_session
                              WHERE current_session.robot_id = ?
                                AND current_session.boot_id = current_boot_id
                          )
                          AND (
                              telemetry_sampled_at IS NULL
                              OR ? > telemetry_sampled_at
                          )
                      )
                  )
                """,
                payload.bootId(),
                robotId,
                payload.bootId(),
                receivedAt,
                receivedAt,
                mission == null ? null : mission.state().name(),
                battery.valid(),
                battery.percent(),
                battery.state().name(),
                enumName(battery.source()),
                battery.voltageV(),
                battery.currentA(),
                battery.charging(),
                battery.batteryTemperatureC(),
                battery.estimatedRuntimeSec(),
                sampledAt,
                payload.seq(),
                sampledAt,
                receivedAt,
                position.valid(),
                position.valid(),
                position.lon(),
                position.lat(),
                position.valid(),
                position.headingDeg(),
                motion.moving(),
                motion.speedMps(),
                payload.system().jetsonTempC(),
                receivedAt,
                receivedAt,
                robotId,
                payload.bootId(),
                payload.seq(),
                sampledAt,
                payload.bootId(),
                robotId,
                payload.bootId(),
                robotId,
                sampledAt);
        return updated == 1;
    }

    private OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
