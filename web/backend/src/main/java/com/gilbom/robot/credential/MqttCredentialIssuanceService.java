package com.gilbom.robot.credential;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.robot.data.application.result.IssuedMqttCredential;

@Service
public class MqttCredentialIssuanceService {

    private static final String CREDENTIAL_TYPE = "MQTT_PASSWORD";
    private static final String REQUEST_METHOD = "POST";

    private final JdbcTemplate jdbcTemplate;
    private final MqttCredentialSecretGenerator secretGenerator;
    private final MqttCredentialProvisioner provisioner;
    private final MqttCredentialProvisioningProperties properties;

    public MqttCredentialIssuanceService(
            JdbcTemplate jdbcTemplate,
            MqttCredentialSecretGenerator secretGenerator,
            MqttCredentialProvisioner provisioner,
            MqttCredentialProvisioningProperties properties
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.secretGenerator = secretGenerator;
        this.provisioner = provisioner;
        this.properties = properties;
    }

    @Transactional
    public IssuedMqttCredential issue(
            UUID robotId,
            UUID actorUserId,
            UUID idempotencyKey,
            UUID requestId
    ) {
        requireTransactionSynchronization();
        String brokerUri = publicBrokerUri();
        String requestPath = "/api/v1/robots/" + robotId + "/credentials";
        byte[] principalKeyHash = sha256("USER\u0000" + actorUserId);
        byte[] idempotencyKeyHash = sha256(idempotencyKey.toString());
        byte[] requestFingerprint = sha256(
                REQUEST_METHOD + "\u0000" + requestPath);

        claimIdempotency(
                principalKeyHash,
                idempotencyKeyHash,
                requestFingerprint,
                requestPath);

        RobotCredentialTarget robot = findRobotForUpdate(robotId);
        if (robot == null) {
            throw new BusinessException(ErrorCode.ROBOT_NOT_FOUND);
        }
        if ("DISABLED".equals(robot.operationalStatus())) {
            throw new BusinessException(
                    ErrorCode.COMMON_BUSINESS_RULE_VIOLATION,
                    "비활성화된 로봇에는 인증정보를 발급할 수 없습니다.");
        }
        if (hasActiveCredential(robotId)) {
            throw new BusinessException(ErrorCode.ROBOT_CREDENTIAL_CONFLICT);
        }

        int credentialVersion = nextCredentialVersion(robotId);
        String password = secretGenerator.generate();
        MqttCredentialProvisioner.ProvisionedMqttCredential provisioned =
                provisioner.provision(robot.robotCode(), password);
        registerRollbackCleanup(robot.robotCode());

        UUID credentialId = UUID.randomUUID();
        String fingerprint = HexFormat.of().formatHex(sha256(password));
        CredentialTimes times = insertActiveCredential(
                credentialId,
                robotId,
                credentialVersion,
                provisioned.passwordHash(),
                fingerprint,
                actorUserId);

        jdbcTemplate.update("""
                UPDATE robot.robot
                SET operational_status = 'ACTIVE',
                    updated_at = NOW()
                WHERE robot_id = ?
                """,
                robotId);

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
                    'ROBOT_CREDENTIAL_ISSUED',
                    'ROBOT_CREDENTIAL',
                    ?,
                    jsonb_build_object(
                        'robotId', ?::text,
                        'robotCode', ?,
                        'credentialType', ?,
                        'credentialVersion', ?,
                        'credentialStatus', 'ACTIVE',
                        'expiresAt', ?::timestamptz
                    ),
                    'SUCCESS'
                )
                """,
                requestId,
                actorUserId,
                credentialId,
                robotId,
                robot.robotCode(),
                CREDENTIAL_TYPE,
                credentialVersion,
                Timestamp.from(times.expiresAt()));

        storeSafeIdempotentResponse(
                principalKeyHash,
                idempotencyKeyHash,
                credentialId,
                robot.robotCode(),
                times.expiresAt(),
                requestPath);

        return new IssuedMqttCredential(
                credentialId,
                CREDENTIAL_TYPE,
                brokerUri,
                robot.robotCode(),
                password,
                robot.robotCode(),
                times.expiresAt());
    }

    private void claimIdempotency(
            byte[] principalKeyHash,
            byte[] idempotencyKeyHash,
            byte[] requestFingerprint,
            String requestPath
    ) {
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
                ON CONFLICT (principal_key_hash, idempotency_key_hash)
                DO NOTHING
                """,
                principalKeyHash,
                idempotencyKeyHash,
                requestFingerprint,
                REQUEST_METHOD,
                requestPath);
        if (claimed == 1) {
            return;
        }

        ExistingIdempotencyRequest existing = jdbcTemplate.query("""
                        SELECT
                            request_fingerprint,
                            request_method,
                            request_path,
                            processing_status
                        FROM request_control.idempotency_request
                        WHERE principal_key_hash = ?
                          AND idempotency_key_hash = ?
                        """,
                resultSet -> resultSet.next()
                        ? new ExistingIdempotencyRequest(
                                resultSet.getBytes("request_fingerprint"),
                                resultSet.getString("request_method"),
                                resultSet.getString("request_path"),
                                resultSet.getString("processing_status"))
                        : null,
                principalKeyHash,
                idempotencyKeyHash);

        if (existing == null) {
            throw new IllegalStateException("Idempotency request disappeared");
        }
        if (!Arrays.equals(existing.requestFingerprint(), requestFingerprint)
                || !REQUEST_METHOD.equals(existing.requestMethod())
                || !requestPath.equals(existing.requestPath())) {
            throw new BusinessException(
                    ErrorCode.COMMON_CONFLICT,
                    "동일한 멱등키를 다른 요청에 사용할 수 없습니다.");
        }
        if ("SUCCEEDED".equals(existing.processingStatus())) {
            throw new BusinessException(
                    ErrorCode.ROBOT_CREDENTIAL_SECRET_ALREADY_ISSUED);
        }
        throw new BusinessException(
                ErrorCode.COMMON_CONFLICT,
                "동일한 인증정보 발급 요청이 처리 중입니다.");
    }

    private RobotCredentialTarget findRobotForUpdate(UUID robotId) {
        return jdbcTemplate.query("""
                        SELECT robot_code, operational_status
                        FROM robot.robot
                        WHERE robot_id = ?
                        FOR UPDATE
                        """,
                resultSet -> resultSet.next()
                        ? new RobotCredentialTarget(
                                resultSet.getString("robot_code"),
                                resultSet.getString("operational_status"))
                        : null,
                robotId);
    }

    private boolean hasActiveCredential(UUID robotId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM robot.robot_credential
                    WHERE robot_id = ?
                      AND credential_type = ?
                      AND credential_status = 'ACTIVE'
                )
                """,
                Boolean.class,
                robotId,
                CREDENTIAL_TYPE);
        return Boolean.TRUE.equals(exists);
    }

    private int nextCredentialVersion(UUID robotId) {
        Integer version = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(credential_version), 0) + 1
                FROM robot.robot_credential
                WHERE robot_id = ?
                  AND credential_type = ?
                """,
                Integer.class,
                robotId,
                CREDENTIAL_TYPE);
        if (version == null) {
            throw new IllegalStateException("Credential version was not generated");
        }
        return version;
    }

    private CredentialTimes insertActiveCredential(
            UUID credentialId,
            UUID robotId,
            int credentialVersion,
            String passwordHash,
            String fingerprint,
            UUID actorUserId
    ) {
        return jdbcTemplate.query("""
                        INSERT INTO robot.robot_credential (
                            credential_id,
                            robot_id,
                            credential_type,
                            credential_version,
                            secret_hash,
                            secret_fingerprint,
                            credential_status,
                            issued_by,
                            expires_at,
                            activated_at
                        ) VALUES (
                            ?,
                            ?,
                            ?,
                            ?,
                            ?,
                            ?,
                            'ACTIVE',
                            ?,
                            NOW() + INTERVAL '180 days',
                            NOW()
                        )
                        RETURNING issued_at, expires_at
                        """,
                resultSet -> {
                    if (!resultSet.next()) {
                        throw new IllegalStateException(
                                "Credential insert returned no row");
                    }
                    return new CredentialTimes(
                            resultSet.getTimestamp("issued_at").toInstant(),
                            resultSet.getTimestamp("expires_at").toInstant());
                },
                credentialId,
                robotId,
                CREDENTIAL_TYPE,
                credentialVersion,
                passwordHash,
                fingerprint,
                actorUserId);
    }

    private void storeSafeIdempotentResponse(
            byte[] principalKeyHash,
            byte[] idempotencyKeyHash,
            UUID credentialId,
            String username,
            Instant expiresAt,
            String requestPath
    ) {
        int updated = jdbcTemplate.update("""
                UPDATE request_control.idempotency_request
                SET processing_status = 'SUCCEEDED',
                    response_status = 201,
                    response_headers = jsonb_build_object(
                        'Location',
                        CONCAT(?, '/', ?::text),
                        'Cache-Control',
                        'no-store',
                        'Pragma',
                        'no-cache'
                    ),
                    response_body = jsonb_build_object(
                        'credentialId', ?::text,
                        'credentialType', ?,
                        'username', ?,
                        'expiresAt', ?::timestamptz,
                        'secretReplayable', false
                    ),
                    updated_at = NOW()
                WHERE principal_key_hash = ?
                  AND idempotency_key_hash = ?
                  AND processing_status = 'PROCESSING'
                """,
                requestPath,
                credentialId,
                credentialId,
                CREDENTIAL_TYPE,
                username,
                Timestamp.from(expiresAt),
                principalKeyHash,
                idempotencyKeyHash);
        if (updated != 1) {
            throw new IllegalStateException(
                    "Could not complete credential idempotency request");
        }
    }

    private void registerRollbackCleanup(String username) {
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) {
                            provisioner.remove(username);
                        }
                    }
                });
    }

    private void requireTransactionSynchronization() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException(
                    "Credential issuance requires transaction synchronization");
        }
    }

    private String publicBrokerUri() {
        String value = properties.getPublicBrokerUri();
        if (value == null || value.isBlank()) {
            throw new BusinessException(
                    ErrorCode.MQTT_CREDENTIAL_PROVISIONING_UNAVAILABLE);
        }
        return value.trim();
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record RobotCredentialTarget(
            String robotCode,
            String operationalStatus
    ) {
    }

    private record CredentialTimes(
            Instant issuedAt,
            Instant expiresAt
    ) {
    }

    private record ExistingIdempotencyRequest(
            byte[] requestFingerprint,
            String requestMethod,
            String requestPath,
            String processingStatus
    ) {
    }
}
