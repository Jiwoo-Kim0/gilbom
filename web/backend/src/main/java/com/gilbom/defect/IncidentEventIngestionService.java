package com.gilbom.defect;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.defect.realtime.DefectRealtimeEvent;
import com.gilbom.robot.data.mqtt.v2.inbound.IncidentEventPayload;
import com.gilbom.robot.event.RobotEventIngestionResult;
import com.gilbom.robot.event.RobotEventIngestionService;

@Service
public class IncidentEventIngestionService
        implements ApplicationEventPublisherAware {

    private static final double MERGE_RADIUS_METERS = 2.0;

    private final JdbcTemplate jdbcTemplate;
    private final RobotEventIngestionService eventIngestionService;
    private final ObjectMapper objectMapper;

    /**
     * 생성자를 바꾸면 이 서비스를 직접 생성하는 robot 쪽 테스트가 깨지므로
     * {@link ApplicationEventPublisherAware}로 주입받는다. Spring이 빈 초기화
     * 시점에 주입하고, 직접 생성한 경우에는 no-op으로 남아 발행만 생략된다.
     */
    private ApplicationEventPublisher eventPublisher = event -> {
    };

    public IncidentEventIngestionService(
            JdbcTemplate jdbcTemplate,
            RobotEventIngestionService eventIngestionService,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.eventIngestionService = eventIngestionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void setApplicationEventPublisher(
            ApplicationEventPublisher applicationEventPublisher
    ) {
        this.eventPublisher = applicationEventPublisher;
    }

    @Transactional
    public IncidentIngestionResult ingest(
            IncidentEventPayload payload,
            String rawPayload,
            Instant receivedAt
    ) {
        RobotEventIngestionResult event = eventIngestionService.ingest(
                payload,
                payload.eventType(),
                rawPayload,
                receivedAt);
        if (!event.inserted()) {
            return new IncidentIngestionResult(
                    event,
                    findDefectId(event.eventId()),
                    false);
        }

        IncidentContext context = resolveContext(
                event.missionId(),
                payload.location().zoneId());
        if (!payload.location().valid() || context.zoneId() == null) {
            return new IncidentIngestionResult(event, null, false);
        }

        UUID imageId = linkAvailableImage(
                payload.evidence().imageId(),
                event.robotId(),
                event.eventId());
        UUID defectId = findMergeCandidate(
                payload,
                context.patrolAreaId());
        boolean newDefect = defectId == null;
        if (newDefect) {
            defectId = createDefect(payload, context);
        }

        insertObservation(
                event,
                defectId,
                imageId,
                payload,
                context.zoneId(),
                newDefect);
        if (!newDefect) {
            updateDefectAggregate(defectId, payload);
        }
        publishRealtimeEvent(defectId, newDefect, payload.occurredAt());
        return new IncidentIngestionResult(event, defectId, true);
    }

    /**
     * 계약 24장의 SSE 이벤트를 발행한다. 신규 결함이면 defect.created,
     * 기존 결함에 관측이 합산되면 집계가 바뀌므로 defect.updated다.
     *
     * <p>발행 값은 payload가 아니라 저장된 행에서 읽는다. risk_level처럼 서버가
     * 정하는 값이 있어 payload만으로는 실제 상태를 알 수 없고, 앞으로 위험도 계산이
     * 추가돼도 이벤트가 어긋나지 않는다.
     *
     * <p>{@code DefectRealtimeEventListener}가 AFTER_COMMIT에 수신하므로 이
     * 트랜잭션이 롤백되면 스트림으로 나가지 않는다.
     */
    private void publishRealtimeEvent(
            UUID defectId,
            boolean created,
            Instant occurredAt
    ) {
        DefectRealtimeEvent event = jdbcTemplate.query("""
                        SELECT
                            incident_code,
                            patrol_area_id,
                            defect_type,
                            risk_level,
                            workflow_status,
                            latest_confidence,
                            ST_X(location) AS longitude,
                            ST_Y(location) AS latitude
                        FROM defect.defect
                        WHERE defect_id = ?
                        """,
                resultSet -> {
                    if (!resultSet.next()) {
                        return null;
                    }
                    String incidentCode = resultSet.getString("incident_code");
                    UUID patrolAreaId =
                            resultSet.getObject("patrol_area_id", UUID.class);
                    String defectType = resultSet.getString("defect_type");
                    String riskLevel = resultSet.getString("risk_level");
                    String workflowStatus =
                            resultSet.getString("workflow_status");
                    double longitude = resultSet.getDouble("longitude");
                    double latitude = resultSet.getDouble("latitude");
                    if (created) {
                        return DefectRealtimeEvent.created(
                                defectId,
                                incidentCode,
                                patrolAreaId,
                                defectType,
                                riskLevel,
                                workflowStatus,
                                longitude,
                                latitude,
                                resultSet.getBigDecimal("latest_confidence"),
                                occurredAt);
                    }
                    return DefectRealtimeEvent.updated(
                            defectId,
                            incidentCode,
                            patrolAreaId,
                            defectType,
                            riskLevel,
                            workflowStatus,
                            longitude,
                            latitude,
                            null,
                            null,
                            List.of(
                                    "observationCount",
                                    "lastSeenAt",
                                    "latestConfidence"),
                            occurredAt);
                },
                defectId);
        if (event != null) {
            eventPublisher.publishEvent(event);
        }
    }

    private IncidentContext resolveContext(
            UUID missionId,
            String payloadZoneId
    ) {
        if (missionId != null) {
            IncidentContext context = jdbcTemplate.query("""
                            SELECT
                                m.patrol_area_id,
                                m.patrol_route_id,
                                a.area_code
                            FROM robot.mission m
                            JOIN robot.patrol_area a
                              ON a.patrol_area_id = m.patrol_area_id
                            WHERE m.mission_id = ?
                            """,
                    resultSet -> resultSet.next()
                            ? new IncidentContext(
                                    resultSet.getObject(
                                            "patrol_area_id",
                                            UUID.class),
                                    resultSet.getObject(
                                            "patrol_route_id",
                                            UUID.class),
                                    resultSet.getString("area_code"))
                            : null,
                    missionId);
            if (context == null) {
                throw new BusinessException(ErrorCode.MISSION_NOT_FOUND);
            }
            if (payloadZoneId != null
                    && !payloadZoneId.equals(context.zoneId())) {
                throw new BusinessException(
                        ErrorCode.MQTT_EVENT_CONFLICT,
                        "Incident zone_id does not match the Mission area");
            }
            return context;
        }

        if (payloadZoneId == null) {
            return new IncidentContext(null, null, null);
        }
        UUID areaId = jdbcTemplate.query("""
                        SELECT patrol_area_id
                        FROM robot.patrol_area
                        WHERE area_code = ?
                          AND lifecycle_status = 'ACTIVE'
                        """,
                resultSet -> resultSet.next()
                        ? resultSet.getObject(
                                "patrol_area_id",
                                UUID.class)
                        : null,
                payloadZoneId);
        return new IncidentContext(areaId, null, payloadZoneId);
    }

    private UUID linkAvailableImage(
            UUID requestedImageId,
            UUID robotId,
            UUID eventId
    ) {
        if (requestedImageId == null) {
            return null;
        }
        UUID linkedEventId = jdbcTemplate.query("""
                        SELECT event_id
                        FROM storage.robot_image
                        WHERE image_id = ?
                          AND robot_id = ?
                          AND image_status = 'AVAILABLE'
                        FOR UPDATE
                        """,
                resultSet -> resultSet.next()
                        ? resultSet.getObject("event_id", UUID.class)
                        : null,
                requestedImageId,
                robotId);
        boolean imageExists = Boolean.TRUE.equals(
                jdbcTemplate.queryForObject("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM storage.robot_image
                            WHERE image_id = ?
                              AND robot_id = ?
                              AND image_status = 'AVAILABLE'
                        )
                        """,
                        Boolean.class,
                        requestedImageId,
                        robotId));
        if (!imageExists) {
            return null;
        }
        if (linkedEventId != null && !linkedEventId.equals(eventId)) {
            throw new BusinessException(ErrorCode.MQTT_EVENT_CONFLICT);
        }
        if (linkedEventId == null) {
            jdbcTemplate.update("""
                    UPDATE storage.robot_image
                    SET event_id = ?,
                        updated_at = NOW()
                    WHERE image_id = ?
                      AND robot_id = ?
                      AND event_id IS NULL
                    """,
                    eventId,
                    requestedImageId,
                    robotId);
        }
        return requestedImageId;
    }

    private UUID findMergeCandidate(
            IncidentEventPayload payload,
            UUID patrolAreaId
    ) {
        List<UUID> candidates = jdbcTemplate.query("""
                SELECT d.defect_id
                FROM defect.defect d
                WHERE d.merged_into_defect_id IS NULL
                  AND d.defect_type = ?
                  AND d.block_type = ?
                  AND d.patrol_area_id IS NOT DISTINCT FROM ?
                  AND ST_DWithin(
                      d.location::geography,
                      ST_SetSRID(
                          ST_MakePoint(?, ?),
                          4326
                      )::geography,
                      ?
                  )
                ORDER BY
                    ST_Distance(
                        d.location::geography,
                        ST_SetSRID(
                            ST_MakePoint(?, ?),
                            4326
                        )::geography
                    ) ASC,
                    d.last_seen_at DESC,
                    d.defect_id ASC
                LIMIT 1
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> resultSet.getObject(
                        "defect_id",
                        UUID.class),
                payload.incident().defectType().name(),
                payload.incident().blockType().name(),
                patrolAreaId,
                payload.location().lon(),
                payload.location().lat(),
                MERGE_RADIUS_METERS,
                payload.location().lon(),
                payload.location().lat());
        return candidates.stream().findFirst().orElse(null);
    }

    private UUID createDefect(
            IncidentEventPayload payload,
            IncidentContext context
    ) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO defect.defect (
                    incident_code,
                    asset_type,
                    block_type,
                    defect_type,
                    location,
                    zone_id,
                    patrol_area_id,
                    patrol_route_id,
                    first_seen_at,
                    last_seen_at,
                    latest_confidence,
                    review_required
                ) VALUES (
                    ?,
                    ?,
                    ?,
                    ?,
                    ST_SetSRID(ST_MakePoint(?, ?), 4326),
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?
                )
                RETURNING defect_id
                """,
                UUID.class,
                "INC-" + payload.eventId(),
                payload.incident().assetType().name(),
                payload.incident().blockType().name(),
                payload.incident().defectType().name(),
                payload.location().lon(),
                payload.location().lat(),
                context.zoneId(),
                context.patrolAreaId(),
                context.patrolRouteId(),
                utc(payload.occurredAt()),
                utc(payload.occurredAt()),
                payload.incident().confidence(),
                payload.incident().confidence() < 0.7);
    }

    private void insertObservation(
            RobotEventIngestionResult event,
            UUID defectId,
            UUID imageId,
            IncidentEventPayload payload,
            String zoneId,
            boolean representative
    ) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO defect.defect_observation (
                    event_id,
                    robot_id,
                    defect_id,
                    location_valid,
                    location,
                    zone_id,
                    asset_type,
                    block_type,
                    detected_defect_type,
                    confidence,
                    damage_ratio,
                    attributes,
                    image_id,
                    evidence_captured_at,
                    image_width,
                    image_height,
                    bbox_norm,
                    model_name,
                    model_version,
                    is_representative,
                    created_at
                ) VALUES (
                    ?,
                    ?,
                    ?,
                    TRUE,
                    ST_SetSRID(ST_MakePoint(?, ?), 4326),
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    CAST(? AS JSONB),
                    ?,
                    ?,
                    ?,
                    ?,
                    CAST(? AS JSONB),
                    ?,
                    ?,
                    ?,
                    ?
                )
                """,
                event.eventId(),
                event.robotId(),
                defectId,
                payload.location().lon(),
                payload.location().lat(),
                zoneId,
                payload.incident().assetType().name(),
                payload.incident().blockType().name(),
                payload.incident().defectType().name(),
                payload.incident().confidence(),
                payload.incident().attributes() == null
                        ? null
                        : payload.incident().attributes().damageRatio(),
                payload.incident().attributes() == null
                        ? null
                        : json(payload.incident().attributes()),
                imageId,
                utc(payload.evidence().capturedAt()),
                payload.evidence().imageWidth(),
                payload.evidence().imageHeight(),
                json(payload.evidence().bboxNorm()),
                payload.model().name(),
                payload.model().version(),
                representative,
                utc(payload.occurredAt()));
        if (inserted != 1) {
            throw new IllegalStateException(
                    "Defect observation was not persisted");
        }
    }

    private void updateDefectAggregate(
            UUID defectId,
            IncidentEventPayload payload
    ) {
        int updated = jdbcTemplate.update("""
                UPDATE defect.defect
                SET observation_count = observation_count + 1,
                    latest_confidence = CASE
                        WHEN ? >= last_seen_at THEN ?
                        ELSE latest_confidence
                    END,
                    last_seen_at = GREATEST(last_seen_at, ?),
                    review_required = review_required OR ?,
                    updated_at = NOW()
                WHERE defect_id = ?
                """,
                utc(payload.occurredAt()),
                payload.incident().confidence(),
                utc(payload.occurredAt()),
                payload.incident().confidence() < 0.7,
                defectId);
        if (updated != 1) {
            throw new IllegalStateException(
                    "Defect aggregate was not updated");
        }
    }

    private UUID findDefectId(UUID eventId) {
        return jdbcTemplate.query("""
                        SELECT defect_id
                        FROM defect.defect_observation
                        WHERE event_id = ?
                        """,
                resultSet -> resultSet.next()
                        ? resultSet.getObject("defect_id", UUID.class)
                        : null,
                eventId);
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

    public record IncidentIngestionResult(
            RobotEventIngestionResult event,
            UUID defectId,
            boolean observationInserted
    ) {
    }

    private record IncidentContext(
            UUID patrolAreaId,
            UUID patrolRouteId,
            String zoneId
    ) {
    }
}
