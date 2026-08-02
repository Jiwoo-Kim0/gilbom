package com.gilbom.robot.command;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
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
import com.gilbom.robot.data.api.request.RobotCommandCreateRequest;
import com.gilbom.robot.data.mqtt.v2.outbound.CommandRequestPayload;

@Service
public class RobotCommandCreationService {

    private static final String REQUEST_METHOD = "POST";
    private static final Set<String> AUDIT_FIELDS = Set.of(
            "robotId",
            "mqttId",
            "commandType",
            "commandStatus",
            "expiresAt"
    );

    private final JdbcTemplate jdbcTemplate;
    private final RobotScopeService scopeService;
    private final RobotCommandRepository repository;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RobotCommandCreationService(
            JdbcTemplate jdbcTemplate,
            RobotScopeService scopeService,
            RobotCommandRepository repository,
            AuditLogService auditLogService,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.scopeService = scopeService;
        this.repository = repository;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper.copy().setPropertyNamingStrategy(
                PropertyNamingStrategies.SNAKE_CASE);
        this.clock = clock;
    }

    @Transactional
    public CreatedCommand create(
            UUID robotId,
            RobotCommandCreateRequest request,
            UUID actorUserId,
            UUID idempotencyKey,
            AuditRequestContext requestContext
    ) {
        String requestPath = "/api/v1/robots/" + robotId + "/commands";
        byte[] principalKeyHash = sha256("USER\u0000" + actorUserId);
        byte[] idempotencyKeyHash = sha256(idempotencyKey.toString());
        byte[] requestFingerprint = sha256(
                REQUEST_METHOD + "\u0000"
                        + requestPath + "\u0000"
                        + request.commandType() + "\u0000"
                        + serialize(request.parameters()));

        int claimed = jdbcTemplate.update("""
                INSERT INTO request_control.idempotency_request (
                    principal_type,
                    principal_key_hash,
                    idempotency_key_hash,
                    request_fingerprint,
                    request_method,
                    request_path,
                    lease_expires_at,
                    expires_at
                ) VALUES (
                    'USER',
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    NOW() + INTERVAL '1 minute',
                    NOW() + INTERVAL '24 hours'
                )
                ON CONFLICT (
                    principal_key_hash,
                    idempotency_key_hash
                ) DO NOTHING
                """,
                principalKeyHash,
                idempotencyKeyHash,
                requestFingerprint,
                REQUEST_METHOD,
                requestPath);

        if (claimed == 0) {
            return new CreatedCommand(
                    replayOrReject(
                            principalKeyHash,
                            idempotencyKeyHash,
                            requestFingerprint,
                            requestPath),
                    false);
        }

        String mqttId = scopeService.requireOperableMqttId(
                actorUserId,
                robotId);
        String actorUserCode = requireActorUserCode(actorUserId);
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(ttl(request.commandType()));
        UUID commandId = UUID.randomUUID();
        MissionCommandTarget missionTarget = resolveMissionTarget(
                robotId,
                request);

        jdbcTemplate.update("""
                INSERT INTO robot.robot_command (
                    command_id,
                    schema_version,
                    robot_id,
                    mission_id,
                    patrol_area_id,
                    command_type,
                    command_status,
                    parameters,
                    requested_by_type,
                    requested_by_user_id,
                    issued_at,
                    expires_at
                ) VALUES (
                    ?,
                    '2.0',
                    ?,
                    ?,
                    ?,
                    ?,
                    'CREATED',
                    ?::jsonb,
                    'ADMIN',
                    ?,
                    ?,
                    ?
                )
                """,
                commandId,
                robotId,
                missionTarget == null ? null : missionTarget.missionId(),
                missionTarget == null ? null : missionTarget.patrolAreaId(),
                request.commandType().name(),
                serialize(request.parameters()),
                actorUserId,
                utc(issuedAt),
                utc(expiresAt));

        auditLogService.appendRequired(new AuditLogRecord(
                requestContext,
                actorUserId,
                AuditActorType.USER,
                AuditAction.ROBOT_COMMAND_ISSUED.databaseValue(),
                AuditTargetType.ROBOT_COMMAND,
                commandId,
                null,
                AuditSnapshot.allow(
                        AUDIT_FIELDS,
                        Map.of(
                                "robotId", robotId,
                                "mqttId", mqttId,
                                "commandType", request.commandType(),
                                "commandStatus", RobotCommandStatus.CREATED,
                                "expiresAt", expiresAt)),
                null,
                AuditResult.SUCCESS));

        storeIdempotentResponse(
                principalKeyHash,
                idempotencyKeyHash,
                commandId);

        RobotCommandRecord command = repository.findById(commandId)
                .orElseThrow(() -> new IllegalStateException(
                        "Created command was not found"));
        if (!actorUserCode.equals(command.requestedByUserCode())) {
            throw new IllegalStateException(
                    "Command requester code does not match actor");
        }
        return new CreatedCommand(command, true);
    }

    private MissionCommandTarget resolveMissionTarget(
            UUID robotId,
            RobotCommandCreateRequest request
    ) {
        return switch (request.commandType()) {
            case START_MISSION -> resolveStartMissionTarget(robotId, request);
            case PAUSE_MISSION -> resolveActiveMissionTarget(
                    robotId,
                    request.commandType(),
                    Set.of("RUNNING"));
            case RESUME_MISSION -> resolveActiveMissionTarget(
                    robotId,
                    request.commandType(),
                    Set.of("PAUSED"));
            case COMPLETE_MISSION -> resolveActiveMissionTarget(
                    robotId,
                    request.commandType(),
                    Set.of("RUNNING"));
            case CANCEL_MISSION -> resolveActiveMissionTarget(
                    robotId,
                    request.commandType(),
                    Set.of("READY", "RUNNING", "PAUSED"));
            case EMERGENCY_STOP, CLEAR_EMERGENCY_STOP -> null;
        };
    }

    private MissionCommandTarget resolveStartMissionTarget(
            UUID robotId,
            RobotCommandCreateRequest request
    ) {
        MissionCommandTarget target = jdbcTemplate.query("""
                        SELECT
                            m.mission_id,
                            m.patrol_area_id,
                            a.area_code
                        FROM robot.mission m
                        JOIN robot.patrol_area a
                          ON a.patrol_area_id = m.patrol_area_id
                        WHERE m.mission_code = ?
                          AND m.robot_id = ?
                          AND m.deleted_at IS NULL
                          AND m.status IN ('CREATED', 'READY')
                        """,
                resultSet -> resultSet.next()
                        ? new MissionCommandTarget(
                                resultSet.getObject(
                                        "mission_id",
                                        UUID.class),
                                resultSet.getObject(
                                        "patrol_area_id",
                                        UUID.class),
                                resultSet.getString("area_code"))
                        : null,
                request.parameters().missionId(),
                robotId);
        if (target == null) {
            throw new BusinessException(ErrorCode.MISSION_NOT_FOUND);
        }
        if (!target.areaCode().equals(request.parameters().zoneId())) {
            throw new BusinessException(
                    ErrorCode.COMMON_BUSINESS_RULE_VIOLATION,
                    "Mission 순찰 구역과 zoneId가 일치하지 않습니다.");
        }
        return target;
    }

    private MissionCommandTarget resolveActiveMissionTarget(
            UUID robotId,
            CommandRequestPayload.CommandType commandType,
            Set<String> allowedStatuses
    ) {
        String statusPlaceholders = String.join(
                ", ",
                allowedStatuses.stream().map(ignored -> "?").toList());
        Object[] arguments = new Object[allowedStatuses.size() + 1];
        arguments[0] = robotId;
        int index = 1;
        for (String status : allowedStatuses) {
            arguments[index++] = status;
        }

        MissionCommandTarget target = jdbcTemplate.query("""
                        SELECT
                            m.mission_id,
                            m.patrol_area_id,
                            a.area_code
                        FROM robot.mission m
                        JOIN robot.patrol_area a
                          ON a.patrol_area_id = m.patrol_area_id
                        WHERE m.robot_id = ?
                          AND m.deleted_at IS NULL
                          AND m.status IN (%s)
                        ORDER BY m.updated_at DESC, m.mission_id
                        LIMIT 1
                        """.formatted(statusPlaceholders),
                resultSet -> resultSet.next()
                        ? new MissionCommandTarget(
                                resultSet.getObject(
                                        "mission_id",
                                        UUID.class),
                                resultSet.getObject(
                                        "patrol_area_id",
                                        UUID.class),
                                resultSet.getString("area_code"))
                        : null,
                arguments);
        if (target == null) {
            String currentStatus = jdbcTemplate.query("""
                            SELECT status
                            FROM robot.mission
                            WHERE robot_id = ?
                              AND deleted_at IS NULL
                              AND status IN (
                                  'CREATED',
                                  'READY',
                                  'RUNNING',
                                  'PAUSED'
                              )
                            ORDER BY updated_at DESC, mission_id
                            LIMIT 1
                            """,
                    resultSet -> resultSet.next()
                            ? resultSet.getString("status")
                            : null,
                    robotId);
            if (currentStatus != null) {
                throw new BusinessException(
                        ErrorCode.MISSION_STATE_CONFLICT,
                        missionStateConflictMessage(
                                commandType,
                                currentStatus));
            }
            throw new BusinessException(ErrorCode.MISSION_NOT_FOUND);
        }
        return target;
    }

    private static String missionStateConflictMessage(
            CommandRequestPayload.CommandType commandType,
            String currentStatus
    ) {
        return switch (commandType) {
            case PAUSE_MISSION -> "현재 임무는 " + currentStatus
                    + " 상태입니다. RUNNING 임무만 일시정지할 수 있습니다.";
            case RESUME_MISSION -> "현재 임무는 " + currentStatus
                    + " 상태입니다. PAUSED 임무만 재개할 수 있습니다.";
            case COMPLETE_MISSION -> "현재 임무는 " + currentStatus
                    + " 상태입니다. PAUSED 상태라면 먼저 재개한 뒤 완료해주세요.";
            case CANCEL_MISSION -> "현재 임무는 " + currentStatus
                    + " 상태입니다. READY·RUNNING·PAUSED 임무만 취소할 수 있습니다.";
            default -> ErrorCode.MISSION_STATE_CONFLICT.defaultMessage();
        };
    }

    private RobotCommandRecord replayOrReject(
            byte[] principalKeyHash,
            byte[] idempotencyKeyHash,
            byte[] requestFingerprint,
            String requestPath
    ) {
        ExistingRequest existing = jdbcTemplate.query("""
                        SELECT
                            request_fingerprint,
                            request_method,
                            request_path,
                            processing_status,
                            response_body ->> 'commandId' AS command_id
                        FROM request_control.idempotency_request
                        WHERE principal_key_hash = ?
                          AND idempotency_key_hash = ?
                        """,
                resultSet -> {
                    if (!resultSet.next()) {
                        return null;
                    }
                    String commandId = resultSet.getString("command_id");
                    return new ExistingRequest(
                            resultSet.getBytes("request_fingerprint"),
                            resultSet.getString("request_method"),
                            resultSet.getString("request_path"),
                            resultSet.getString("processing_status"),
                            commandId == null
                                    ? null
                                    : UUID.fromString(commandId));
                },
                principalKeyHash,
                idempotencyKeyHash);
        if (existing == null) {
            throw new IllegalStateException(
                    "Idempotency request disappeared");
        }
        if (!Arrays.equals(
                existing.requestFingerprint(),
                requestFingerprint)
                || !REQUEST_METHOD.equals(existing.requestMethod())
                || !requestPath.equals(existing.requestPath())) {
            throw new BusinessException(
                    ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "같은 Idempotency-Key를 다른 요청에 사용할 수 없습니다.");
        }
        if (!"SUCCEEDED".equals(existing.processingStatus())
                || existing.commandId() == null) {
            throw new BusinessException(
                    ErrorCode.COMMON_CONFLICT,
                    "같은 로봇 명령 요청을 처리 중입니다.");
        }
        return repository.findById(existing.commandId())
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotent command response was not found"));
    }

    private void storeIdempotentResponse(
            byte[] principalKeyHash,
            byte[] idempotencyKeyHash,
            UUID commandId
    ) {
        int updated = jdbcTemplate.update("""
                UPDATE request_control.idempotency_request
                SET processing_status = 'SUCCEEDED',
                    response_status = 201,
                    response_headers = jsonb_build_object(
                        'Location',
                        CONCAT('/api/v1/commands/', ?::text)
                    ),
                    response_body = jsonb_build_object(
                        'commandId',
                        ?::text
                    ),
                    updated_at = NOW()
                WHERE principal_key_hash = ?
                  AND idempotency_key_hash = ?
                  AND processing_status = 'PROCESSING'
                """,
                commandId,
                commandId,
                principalKeyHash,
                idempotencyKeyHash);
        if (updated != 1) {
            throw new IllegalStateException(
                    "Could not complete idempotency request");
        }
    }

    private String requireActorUserCode(UUID actorUserId) {
        String userCode = jdbcTemplate.query("""
                        SELECT user_code
                        FROM auth.admin_user
                        WHERE user_id = ?
                          AND account_status = 'ACTIVE'
                          AND is_active = TRUE
                          AND deleted_at IS NULL
                        """,
                resultSet -> resultSet.next()
                        ? resultSet.getString("user_code")
                        : null,
                actorUserId);
        if (userCode == null) {
            throw new BusinessException(ErrorCode.AUTH_USER_NOT_FOUND);
        }
        return userCode;
    }

    private static Duration ttl(
            CommandRequestPayload.CommandType commandType
    ) {
        return switch (commandType) {
            case EMERGENCY_STOP -> Duration.ofSeconds(10);
            case PAUSE_MISSION,
                    RESUME_MISSION,
                    COMPLETE_MISSION,
                    CANCEL_MISSION,
                    CLEAR_EMERGENCY_STOP -> Duration.ofSeconds(30);
            case START_MISSION -> Duration.ofSeconds(60);
        };
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.COMMON_INTERNAL_ERROR);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    exception);
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record CreatedCommand(
            RobotCommandRecord command,
            boolean newlyCreated
    ) {
    }

    private record ExistingRequest(
            byte[] requestFingerprint,
            String requestMethod,
            String requestPath,
            String processingStatus,
            UUID commandId
    ) {
    }

    private record MissionCommandTarget(
            UUID missionId,
            UUID patrolAreaId,
            String areaCode
    ) {
    }
}
