package com.gilbom.robot.mission;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gilbom.audit.AuditAction;
import com.gilbom.audit.AuditActorType;
import com.gilbom.audit.AuditLogRecord;
import com.gilbom.audit.AuditLogService;
import com.gilbom.audit.AuditRequestContext;
import com.gilbom.audit.AuditResult;
import com.gilbom.audit.AuditSnapshot;
import com.gilbom.audit.AuditTargetType;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.robot.application.RobotScopeService;
import com.gilbom.robot.data.api.response.MissionDtos.MissionCreateRequest;
import com.gilbom.robot.data.api.response.MissionDtos.MissionResponse;
import com.gilbom.robot.data.api.response.MissionDtos.MissionStatus;
import com.gilbom.robot.data.api.response.MissionDtos.MissionUpdateRequest;

@Service
public class MissionManagementService {

    private static final Set<String> AUDIT_FIELDS = Set.of(
            "missionCode",
            "robotId",
            "patrolAreaId",
            "patrolRouteId",
            "plannedDurationSec",
            "status",
            "scheduledStartAt",
            "startedAt",
            "endedAt",
            "progressPercent",
            "completionReason"
    );
    private static final Set<MissionStatus> ADMIN_STATUS_TARGETS = Set.of(
            MissionStatus.READY,
            MissionStatus.CANCELLED,
            MissionStatus.FAILED
    );
    private static final String OWNER_SCOPE = """
            (
            """ + RobotScopeService.SYSTEM_ADMIN_SCOPE + """
                OR EXISTS (
                    SELECT 1
                    FROM auth.user_robot_assignment assignment
                    WHERE assignment.user_id = :userId
                      AND assignment.robot_id = r.robot_id
                      AND assignment.assignment_type = 'OWNER'
                      AND assignment.is_active = TRUE
                      AND (
                          assignment.expires_at IS NULL
                          OR assignment.expires_at > CURRENT_TIMESTAMP
                      )
                )
            )
            """;

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;
    private final MissionQueryService queryService;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public MissionManagementService(
            JdbcTemplate jdbcTemplate,
            NamedParameterJdbcTemplate namedJdbcTemplate,
            MissionQueryService queryService,
            AuditLogService auditLogService,
            Clock clock
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbcTemplate = namedJdbcTemplate;
        this.queryService = queryService;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    @Transactional
    public MissionResponse create(
            MissionCreateRequest request,
            UUID actorUserId,
            AuditRequestContext requestContext
    ) {
        requireOwner(
                actorUserId,
                request.robotId(),
                ErrorCode.ROBOT_NOT_FOUND);
        requireActiveAreaAndRoute(
                request.patrolAreaId(),
                request.patrolRouteId());
        Instant now = clock.instant();
        requireFutureSchedule(request.scheduledStartAt(), now);

        UUID missionId = UUID.randomUUID();
        String missionCode = "MIS-" + missionId.toString()
                .toUpperCase(Locale.ROOT);
        OffsetDateTime nowUtc = utc(now);
        try {
            jdbcTemplate.update("""
                    INSERT INTO robot.mission (
                        mission_id,
                        mission_code,
                        robot_id,
                        patrol_area_id,
                        patrol_route_id,
                        planned_duration_sec,
                        status,
                        scheduled_start_at,
                        created_by,
                        updated_by,
                        created_at,
                        updated_at
                    ) VALUES (
                        ?,
                        ?,
                        ?,
                        ?,
                        ?,
                        ?,
                        'CREATED',
                        ?,
                        ?,
                        ?,
                        ?,
                        ?
                    )
                    """,
                    missionId,
                    missionCode,
                    request.robotId(),
                    request.patrolAreaId(),
                    request.patrolRouteId(),
                    request.plannedDurationSec(),
                    utc(request.scheduledStartAt()),
                    actorUserId,
                    actorUserId,
                    nowUtc,
                    nowUtc);
            appendHistory(
                    missionId,
                    null,
                    MissionStatus.CREATED,
                    null,
                    actorUserId,
                    nowUtc);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(
                    ErrorCode.MISSION_STATE_CONFLICT,
                    exception);
        }

        MissionResponse created = queryService.findMission(
                actorUserId,
                missionId);
        auditLogService.appendRequired(new AuditLogRecord(
                requestContext,
                actorUserId,
                AuditActorType.USER,
                AuditAction.MISSION_CREATED.databaseValue(),
                AuditTargetType.MISSION,
                missionId,
                null,
                snapshot(created),
                null,
                AuditResult.SUCCESS));
        return created;
    }

    @Transactional
    public MissionResponse update(
            UUID missionId,
            MissionUpdateRequest request,
            UUID actorUserId,
            AuditRequestContext requestContext
    ) {
        MissionState current = lockMission(missionId);
        requireOwner(
                actorUserId,
                current.robotId(),
                ErrorCode.MISSION_NOT_FOUND);
        MissionResponse before = queryService.findMission(
                actorUserId,
                missionId);
        validateUpdate(current, request);

        UUID targetAreaId = request.patrolAreaId() == null
                ? current.patrolAreaId()
                : request.patrolAreaId();
        UUID targetRouteId = request.patrolRouteId() == null
                ? current.patrolRouteId()
                : request.patrolRouteId();
        if (request.patrolAreaId() != null
                || request.patrolRouteId() != null) {
            requireActiveAreaAndRoute(targetAreaId, targetRouteId);
        }

        Instant now = clock.instant();
        requireFutureSchedule(request.scheduledStartAt(), now);
        OffsetDateTime nowUtc = utc(now);
        List<String> assignments = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        addAssignment(
                assignments,
                values,
                request.patrolAreaId() != null,
                "patrol_area_id = ?",
                targetAreaId);
        addAssignment(
                assignments,
                values,
                request.patrolRouteId() != null,
                "patrol_route_id = ?",
                targetRouteId);
        addAssignment(
                assignments,
                values,
                request.plannedDurationSec() != null,
                "planned_duration_sec = ?",
                request.plannedDurationSec());
        addAssignment(
                assignments,
                values,
                request.scheduledStartAt() != null,
                "scheduled_start_at = ?",
                utc(request.scheduledStartAt()));

        MissionStatus targetStatus = request.status();
        if (targetStatus != null) {
            assignments.add("status = ?");
            values.add(targetStatus.name());
            if (isTerminal(targetStatus)) {
                assignments.add("ended_at = ?");
                values.add(nowUtc);
                assignments.add("completion_reason = ?");
                values.add(request.completionReason().trim());
                assignments.add("""
                        result_duration_sec = CASE
                            WHEN started_at IS NULL THEN 0
                            ELSE GREATEST(
                                0,
                                FLOOR(
                                    EXTRACT(EPOCH FROM (? - started_at))
                                )::INTEGER
                            )
                        END
                        """);
                values.add(nowUtc);
            }
        }
        assignments.add("updated_by = ?");
        values.add(actorUserId);
        assignments.add("updated_at = ?");
        values.add(nowUtc);
        values.add(missionId);

        try {
            int updated = jdbcTemplate.update(
                    "UPDATE robot.mission SET "
                            + String.join(", ", assignments)
                            + " WHERE mission_id = ?",
                    values.toArray());
            if (updated != 1) {
                throw new BusinessException(ErrorCode.MISSION_NOT_FOUND);
            }
            if (targetStatus != null) {
                appendHistory(
                        missionId,
                        current.status(),
                        targetStatus,
                        request.completionReason(),
                        actorUserId,
                        nowUtc);
            }
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(
                    ErrorCode.MISSION_STATE_CONFLICT,
                    exception);
        }

        MissionResponse after = queryService.findMission(
                actorUserId,
                missionId);
        auditLogService.appendRequired(new AuditLogRecord(
                requestContext,
                actorUserId,
                AuditActorType.USER,
                AuditAction.MISSION_UPDATED.databaseValue(),
                AuditTargetType.MISSION,
                missionId,
                snapshot(before),
                snapshot(after),
                request.completionReason(),
                AuditResult.SUCCESS));
        return after;
    }

    private MissionState lockMission(UUID missionId) {
        MissionState mission = jdbcTemplate.query("""
                        SELECT
                            robot_id,
                            patrol_area_id,
                            patrol_route_id,
                            status
                        FROM robot.mission
                        WHERE mission_id = ?
                        FOR UPDATE
                        """,
                resultSet -> resultSet.next()
                        ? new MissionState(
                                resultSet.getObject(
                                        "robot_id",
                                        UUID.class),
                                resultSet.getObject(
                                        "patrol_area_id",
                                        UUID.class),
                                resultSet.getObject(
                                        "patrol_route_id",
                                        UUID.class),
                                MissionStatus.valueOf(
                                        resultSet.getString("status")))
                        : null,
                missionId);
        if (mission == null) {
            throw new BusinessException(ErrorCode.MISSION_NOT_FOUND);
        }
        return mission;
    }

    private void requireOwner(
            UUID userId,
            UUID robotId,
            ErrorCode errorCode
    ) {
        Boolean allowed = namedJdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM robot.robot r
                    WHERE r.robot_id = :robotId
                      AND r.operational_status = 'ACTIVE'
                      AND
                """ + OWNER_SCOPE + """
                )
                """,
                Map.of(
                        "userId", userId,
                        "robotId", robotId),
                Boolean.class);
        if (!Boolean.TRUE.equals(allowed)) {
            throw new BusinessException(errorCode);
        }
    }

    private void requireActiveAreaAndRoute(
            UUID patrolAreaId,
            UUID patrolRouteId
    ) {
        String areaStatus = jdbcTemplate.query("""
                SELECT lifecycle_status
                FROM robot.patrol_area
                WHERE patrol_area_id = ?
                FOR KEY SHARE
                """,
                resultSet -> resultSet.next()
                        ? resultSet.getString("lifecycle_status")
                        : null,
                patrolAreaId);
        if (!"ACTIVE".equals(areaStatus)) {
            throw new BusinessException(ErrorCode.PATROL_AREA_NOT_FOUND);
        }

        String routeStatus = jdbcTemplate.query("""
                SELECT lifecycle_status
                FROM robot.patrol_route
                WHERE patrol_route_id = ?
                  AND patrol_area_id = ?
                  AND route_code = 'AUTONOMOUS_DEFAULT'
                FOR KEY SHARE
                """,
                resultSet -> resultSet.next()
                        ? resultSet.getString("lifecycle_status")
                        : null,
                patrolRouteId,
                patrolAreaId);
        if (!"ACTIVE".equals(routeStatus)) {
            throw new BusinessException(ErrorCode.PATROL_ROUTE_NOT_FOUND);
        }
    }

    private void validateUpdate(
            MissionState current,
            MissionUpdateRequest request
    ) {
        boolean metadataChanged = request.patrolAreaId() != null
                || request.patrolRouteId() != null
                || request.plannedDurationSec() != null
                || request.scheduledStartAt() != null;
        if (metadataChanged
                && current.status() != MissionStatus.CREATED
                && current.status() != MissionStatus.READY) {
            throw new BusinessException(ErrorCode.MISSION_STATE_CONFLICT);
        }
        if (request.status() == null) {
            return;
        }
        if (!ADMIN_STATUS_TARGETS.contains(request.status())
                || request.status() == current.status()) {
            throw new BusinessException(ErrorCode.MISSION_STATE_CONFLICT);
        }
    }

    private void requireFutureSchedule(Instant scheduledAt, Instant now) {
        if (scheduledAt != null && scheduledAt.isBefore(now)) {
            throw new BusinessException(
                    ErrorCode.COMMON_BUSINESS_RULE_VIOLATION,
                    "순찰 예약 시각은 현재보다 이전일 수 없습니다.");
        }
    }

    private void appendHistory(
            UUID missionId,
            MissionStatus beforeStatus,
            MissionStatus afterStatus,
            String reason,
            UUID actorUserId,
            OffsetDateTime occurredAt
    ) {
        jdbcTemplate.update("""
                INSERT INTO robot.mission_status_history (
                    mission_id,
                    transition_source,
                    before_status,
                    after_status,
                    transition_reason,
                    changed_by,
                    occurred_at,
                    received_at
                ) VALUES (
                    ?,
                    'ADMIN',
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?
                )
                """,
                missionId,
                beforeStatus == null ? null : beforeStatus.name(),
                afterStatus.name(),
                normalizeReason(reason),
                actorUserId,
                occurredAt,
                occurredAt);
    }

    private AuditSnapshot snapshot(MissionResponse mission) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("missionCode", mission.missionCode());
        values.put("robotId", mission.robotId());
        values.put("patrolAreaId", mission.patrolAreaId());
        values.put("patrolRouteId", mission.patrolRouteId());
        values.put("plannedDurationSec", mission.plannedDurationSec());
        values.put("status", mission.status());
        values.put("scheduledStartAt", mission.scheduledStartAt());
        values.put("startedAt", mission.startedAt());
        values.put("endedAt", mission.endedAt());
        values.put("progressPercent", mission.progressPercent());
        values.put("completionReason", mission.completionReason());
        return AuditSnapshot.allow(AUDIT_FIELDS, values);
    }

    private static void addAssignment(
            List<String> assignments,
            List<Object> values,
            boolean include,
            String assignment,
            Object value
    ) {
        if (include) {
            assignments.add(assignment);
            values.add(value);
        }
    }

    private static boolean isTerminal(MissionStatus status) {
        return status == MissionStatus.CANCELLED
                || status == MissionStatus.FAILED;
    }

    private static String normalizeReason(String reason) {
        return reason == null || reason.isBlank() ? null : reason.trim();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null
                ? null
                : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record MissionState(
            UUID robotId,
            UUID patrolAreaId,
            UUID patrolRouteId,
            MissionStatus status
    ) {
    }
}
