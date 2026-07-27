package com.gilbom.robot.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.robot.data.application.command.RegisterRobotCommand;
import com.gilbom.robot.data.application.result.RegisteredRobot;

@Service
public class RobotRegistrationService {

    private static final String REQUEST_METHOD = "POST";
    private static final String REQUEST_PATH = "/api/v1/robots";
    private static final String OPERATIONAL_STATUS = "PROVISIONING";
    private static final String CONNECTIVITY_STATUS = "OFFLINE";

    private final JdbcTemplate jdbcTemplate;

    public RobotRegistrationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public RegisteredRobot register(
            RegisterRobotCommand command,
            UUID actorUserId,
            UUID idempotencyKey,
            UUID requestId
    ) {
        byte[] principalKeyHash = sha256("USER\u0000" + actorUserId);
        byte[] idempotencyKeyHash = sha256(idempotencyKey.toString());
        byte[] requestFingerprint = sha256(
                REQUEST_METHOD + "\u0000"
                        + REQUEST_PATH + "\u0000"
                        + command.robotName() + "\u0000"
                        + command.serialNumber());

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
                ON CONFLICT (principal_key_hash, idempotency_key_hash) DO NOTHING
                """,
                principalKeyHash,
                idempotencyKeyHash,
                requestFingerprint,
                REQUEST_METHOD,
                REQUEST_PATH);

        if (claimed == 0) {
            return replayOrReject(
                    principalKeyHash,
                    idempotencyKeyHash,
                    requestFingerprint);
        }

        if (serialNumberExists(command.serialNumber())) {
            throw duplicateSerialNumber();
        }

        UUID robotId = UUID.randomUUID();
        String robotCode = nextRobotCode();

        try {
            jdbcTemplate.update("""
                    INSERT INTO robot.robot (
                        robot_id,
                        robot_code,
                        robot_name,
                        serial_number,
                        operational_status,
                        registered_by
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    robotId,
                    robotCode,
                    command.robotName(),
                    command.serialNumber(),
                    OPERATIONAL_STATUS,
                    actorUserId);
        } catch (DuplicateKeyException exception) {
            throw duplicateSerialNumber(exception);
        }

        jdbcTemplate.update("""
                INSERT INTO telemetry.robot_current_state (
                    robot_id,
                    connectivity_status
                ) VALUES (?, ?)
                """,
                robotId,
                CONNECTIVITY_STATUS);

        jdbcTemplate.update("""
                INSERT INTO audit.audit_log (
                    request_id,
                    actor_user_id,
                    actor_type,
                    action_type,
                    target_type,
                    target_id,
                    after_value,
                    result
                ) VALUES (
                    ?,
                    ?,
                    'USER',
                    'ROBOT_REGISTERED',
                    'ROBOT',
                    ?,
                    jsonb_build_object(
                        'robotCode', ?,
                        'operationalStatus', ?,
                        'connectivityStatus', ?
                    ),
                    'SUCCESS'
                )
                """,
                requestId,
                actorUserId,
                robotId,
                robotCode,
                OPERATIONAL_STATUS,
                CONNECTIVITY_STATUS);

        RegisteredRobot registeredRobot = new RegisteredRobot(
                robotId,
                robotCode,
                command.robotName(),
                command.serialNumber(),
                OPERATIONAL_STATUS,
                CONNECTIVITY_STATUS);
        storeIdempotentResponse(
                principalKeyHash,
                idempotencyKeyHash,
                registeredRobot);
        return registeredRobot;
    }

    private boolean serialNumberExists(String serialNumber) {
        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM robot.robot
                    WHERE serial_number = ?
                )
                """, Boolean.class, serialNumber);
        return Boolean.TRUE.equals(exists);
    }

    private String nextRobotCode() {
        Long sequence = jdbcTemplate.queryForObject(
                "SELECT nextval('robot.robot_code_sequence')",
                Long.class);
        if (sequence == null) {
            throw new IllegalStateException("Robot code sequence did not return a value");
        }
        return "RBT-" + String.format(Locale.ROOT, "%03d", sequence);
    }

    private RegisteredRobot replayOrReject(
            byte[] principalKeyHash,
            byte[] idempotencyKeyHash,
            byte[] requestFingerprint
    ) {
        ExistingIdempotencyRequest existing = jdbcTemplate.query("""
                        SELECT
                            request_fingerprint,
                            request_method,
                            request_path,
                            processing_status,
                            response_body ->> 'robotId' AS robot_id,
                            response_body ->> 'robotCode' AS robot_code,
                            response_body ->> 'robotName' AS robot_name,
                            response_body ->> 'serialNumber' AS serial_number,
                            response_body ->> 'operationalStatus' AS operational_status,
                            response_body ->> 'connectivityStatus' AS connectivity_status
                        FROM request_control.idempotency_request
                        WHERE principal_key_hash = ?
                          AND idempotency_key_hash = ?
                        """,
                resultSet -> {
                    if (!resultSet.next()) {
                        return null;
                    }
                    String robotId = resultSet.getString("robot_id");
                    RegisteredRobot response = robotId == null
                            ? null
                            : new RegisteredRobot(
                                    UUID.fromString(robotId),
                                    resultSet.getString("robot_code"),
                                    resultSet.getString("robot_name"),
                                    resultSet.getString("serial_number"),
                                    resultSet.getString("operational_status"),
                                    resultSet.getString("connectivity_status"));
                    return new ExistingIdempotencyRequest(
                            resultSet.getBytes("request_fingerprint"),
                            resultSet.getString("request_method"),
                            resultSet.getString("request_path"),
                            resultSet.getString("processing_status"),
                            response);
                },
                principalKeyHash,
                idempotencyKeyHash);

        if (existing == null) {
            throw new IllegalStateException("Idempotency request disappeared");
        }
        if (!Arrays.equals(existing.requestFingerprint(), requestFingerprint)
                || !REQUEST_METHOD.equals(existing.requestMethod())
                || !REQUEST_PATH.equals(existing.requestPath())) {
            throw new BusinessException(
                    ErrorCode.COMMON_CONFLICT,
                    "동일한 멱등키를 다른 요청에 사용할 수 없습니다.");
        }
        if ("SUCCEEDED".equals(existing.processingStatus())
                && existing.response() != null) {
            return existing.response();
        }
        throw new BusinessException(
                ErrorCode.COMMON_CONFLICT,
                "동일한 로봇 등록 요청을 처리 중입니다.");
    }

    private void storeIdempotentResponse(
            byte[] principalKeyHash,
            byte[] idempotencyKeyHash,
            RegisteredRobot robot
    ) {
        int updated = jdbcTemplate.update("""
                UPDATE request_control.idempotency_request
                SET processing_status = 'SUCCEEDED',
                    response_status = 201,
                    response_headers = jsonb_build_object(
                        'Location',
                        CONCAT('/api/v1/robots/', ?::text)
                    ),
                    response_body = jsonb_build_object(
                        'robotId', ?::text,
                        'robotCode', ?,
                        'robotName', ?,
                        'serialNumber', ?,
                        'operationalStatus', ?,
                        'connectivityStatus', ?
                    ),
                    updated_at = NOW()
                WHERE principal_key_hash = ?
                  AND idempotency_key_hash = ?
                  AND processing_status = 'PROCESSING'
                """,
                robot.robotId(),
                robot.robotId(),
                robot.robotCode(),
                robot.robotName(),
                robot.serialNumber(),
                robot.operationalStatus(),
                robot.connectivityStatus(),
                principalKeyHash,
                idempotencyKeyHash);
        if (updated != 1) {
            throw new IllegalStateException("Could not complete idempotency request");
        }
    }

    private BusinessException duplicateSerialNumber() {
        return new BusinessException(ErrorCode.ROBOT_SERIAL_NUMBER_CONFLICT);
    }

    private BusinessException duplicateSerialNumber(DuplicateKeyException cause) {
        return new BusinessException(
                ErrorCode.ROBOT_SERIAL_NUMBER_CONFLICT,
                ErrorCode.ROBOT_SERIAL_NUMBER_CONFLICT.defaultMessage(),
                cause);
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record ExistingIdempotencyRequest(
            byte[] requestFingerprint,
            String requestMethod,
            String requestPath,
            String processingStatus,
            RegisteredRobot response
    ) {
    }
}
