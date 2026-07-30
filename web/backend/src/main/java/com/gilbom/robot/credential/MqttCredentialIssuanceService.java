package com.gilbom.robot.credential;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
import com.gilbom.robot.data.api.response.MqttCredentialResponse;
import com.gilbom.robot.data.application.result.IssuedMqttCredential;

@Service
public class MqttCredentialIssuanceService {

    private static final Logger log = LoggerFactory.getLogger(
            MqttCredentialIssuanceService.class);
    private static final String CREDENTIAL_TYPE = "MQTT_PASSWORD";
    private static final String REQUEST_METHOD = "POST";
    private static final Set<String> AUDIT_FIELDS =
            Set.of(
                    "robotId",
                    "mqttId",
                    "credentialId",
                    "credentialType",
                    "credentialVersion",
                    "credentialStatus",
                    "issuedAt",
                    "expiresAt",
                    "activatedAt",
                    "revokedAt",
                    "revocationReason",
                    "fingerprint"
            );

    private final JdbcTemplate jdbcTemplate;
    private final MqttCredentialSecretGenerator secretGenerator;
    private final MqttCredentialProvisioner provisioner;
    private final MqttCredentialProvisioningProperties properties;
    private final AuditLogService auditLogService;

    public MqttCredentialIssuanceService(
            JdbcTemplate jdbcTemplate,
            MqttCredentialSecretGenerator secretGenerator,
            MqttCredentialProvisioner provisioner,
            MqttCredentialProvisioningProperties properties,
            AuditLogService auditLogService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.secretGenerator = secretGenerator;
        this.provisioner = provisioner;
        this.properties = properties;
        this.auditLogService = auditLogService;
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

        RobotCredentialTarget robot = findRobotForUpdate(
                robotId,
                actorUserId
        );
        if (robot == null) {
            throw new BusinessException(ErrorCode.ROBOT_NOT_FOUND);
        }

        claimIdempotency(
                principalKeyHash,
                idempotencyKeyHash,
                requestFingerprint,
                requestPath);

        if ("DISABLED".equals(robot.operationalStatus())) {
            throw new BusinessException(
                    ErrorCode.COMMON_BUSINESS_RULE_VIOLATION,
                    "비활성화된 로봇에는 인증정보를 발급할 수 없습니다.");
        }
        StoredCredential previousCredential =
                findActiveCredential(robotId);
        int credentialVersion = nextCredentialVersion(robotId);
        String password = secretGenerator.generate();
        MqttCredentialProvisioner.ProvisionedMqttCredential provisioned;
        AuditAction auditAction;
        AuditSnapshot beforeSnapshot = null;
        if (previousCredential == null) {
            provisioned = provisioner.provision(robot.mqttId(), password);
            registerRollbackCleanup(robot.mqttId());
            auditAction = AuditAction.ROBOT_CREDENTIAL_ISSUED;
        } else {
            provisioned = provisioner.rotate(
                    robot.mqttId(),
                    previousCredential.secretHash(),
                    password);
            registerRollbackRestore(
                    robot.mqttId(),
                    previousCredential.secretHash());
            revokeActiveCredential(
                    previousCredential.credentialId(),
                    actorUserId,
                    "ROTATED");
            auditAction = AuditAction.ROBOT_CREDENTIAL_ROTATED;
            beforeSnapshot = auditSnapshot(
                    robotId,
                    robot.mqttId(),
                    previousCredential);
        }

        UUID credentialId = UUID.randomUUID();
        String fingerprint = HexFormat.of().formatHex(sha256(password));
        CredentialTimes times = insertActiveCredential(
                credentialId,
                robotId,
                credentialVersion,
                provisioned.passwordHash(),
                fingerprint,
                actorUserId);

        if (previousCredential == null) {
            jdbcTemplate.update("""
                    UPDATE robot.robot
                    SET operational_status = 'ACTIVE',
                        updated_at = NOW()
                    WHERE robot_id = ?
                    """,
                    robotId);
        }

        StoredCredential issuedCredential = new StoredCredential(
                credentialId,
                CREDENTIAL_TYPE,
                credentialVersion,
                provisioned.passwordHash(),
                fingerprint,
                "ACTIVE",
                actorUserId,
                times.issuedAt(),
                times.expiresAt(),
                times.activatedAt(),
                null,
                null,
                null);

        auditLogService.appendRequired(new AuditLogRecord(
                AuditRequestContext.requestIdOnly(requestId),
                actorUserId,
                AuditActorType.USER,
                auditAction.databaseValue(),
                AuditTargetType.ROBOT_CREDENTIAL,
                credentialId,
                beforeSnapshot,
                auditSnapshot(robotId, robot.mqttId(), issuedCredential),
                null,
                AuditResult.SUCCESS
        ));

        storeSafeIdempotentResponse(
                principalKeyHash,
                idempotencyKeyHash,
                credentialId,
                robot.mqttId(),
                times.expiresAt(),
                requestPath);

        return new IssuedMqttCredential(
                credentialId,
                CREDENTIAL_TYPE,
                brokerUri,
                robot.mqttId(),
                password,
                robot.mqttId(),
                times.expiresAt());
    }

    @Transactional(readOnly = true)
    public List<MqttCredentialResponse> list(
            UUID robotId,
            UUID actorUserId
    ) {
        if (findRobot(robotId, actorUserId) == null) {
            throw new BusinessException(ErrorCode.ROBOT_NOT_FOUND);
        }
        return jdbcTemplate.query("""
                        SELECT
                            credential_id,
                            credential_type,
                            credential_version,
                            credential_status,
                            issued_by,
                            issued_at,
                            expires_at,
                            activated_at,
                            revoked_by,
                            revoked_at,
                            revocation_reason
                        FROM robot.robot_credential
                        WHERE robot_id = ?
                        ORDER BY credential_version DESC
                        """,
                (resultSet, rowNumber) -> new MqttCredentialResponse(
                        resultSet.getObject(
                                "credential_id",
                                UUID.class),
                        resultSet.getString("credential_type"),
                        resultSet.getInt("credential_version"),
                        resultSet.getString("credential_status"),
                        resultSet.getObject("issued_by", UUID.class),
                        resultSet.getTimestamp("issued_at").toInstant(),
                        resultSet.getTimestamp("expires_at").toInstant(),
                        nullableInstant(resultSet.getTimestamp("activated_at")),
                        resultSet.getObject("revoked_by", UUID.class),
                        nullableInstant(resultSet.getTimestamp("revoked_at")),
                        resultSet.getString("revocation_reason")),
                robotId);
    }

    @Transactional
    public MqttCredentialResponse revoke(
            UUID robotId,
            UUID credentialId,
            UUID actorUserId,
            String reason,
            UUID requestId
    ) {
        requireTransactionSynchronization();
        String normalizedReason = normalizeReason(reason);
        RobotCredentialTarget robot = findRobotForUpdate(
                robotId,
                actorUserId);
        if (robot == null) {
            throw new BusinessException(ErrorCode.ROBOT_NOT_FOUND);
        }

        StoredCredential credential = findCredentialForUpdate(
                robotId,
                credentialId);
        if (credential == null) {
            throw new BusinessException(
                    ErrorCode.ROBOT_CREDENTIAL_NOT_FOUND);
        }
        if ("REVOKED".equals(credential.credentialStatus())) {
            return toResponse(credential);
        }
        if (!"ACTIVE".equals(credential.credentialStatus())) {
            throw new BusinessException(
                    ErrorCode.ROBOT_CREDENTIAL_STATE_CONFLICT);
        }

        provisioner.revoke(robot.mqttId(), credential.secretHash());
        registerRollbackRestore(robot.mqttId(), credential.secretHash());
        StoredCredential revoked = revokeActiveCredential(
                credentialId,
                actorUserId,
                normalizedReason);

        auditLogService.appendRequired(new AuditLogRecord(
                AuditRequestContext.requestIdOnly(requestId),
                actorUserId,
                AuditActorType.USER,
                AuditAction.ROBOT_CREDENTIAL_REVOKED.databaseValue(),
                AuditTargetType.ROBOT_CREDENTIAL,
                credentialId,
                auditSnapshot(robotId, robot.mqttId(), credential),
                auditSnapshot(robotId, robot.mqttId(), revoked),
                normalizedReason,
                AuditResult.SUCCESS
        ));
        return toResponse(revoked);
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
                    ErrorCode.IDEMPOTENCY_KEY_REUSED,
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

    private RobotCredentialTarget findRobotForUpdate(
            UUID robotId,
            UUID actorUserId
    ) {
        return jdbcTemplate.query("""
                        WITH actor_scope AS (
                            SELECT EXISTS (
                                SELECT 1
                                FROM auth.admin_user admin_user
                                JOIN auth.user_role user_role
                                  ON user_role.user_id = admin_user.user_id
                                JOIN auth.role role
                                  ON role.role_id = user_role.role_id
                                WHERE admin_user.user_id = ?
                                  AND admin_user.account_status = 'ACTIVE'
                                  AND admin_user.is_active = TRUE
                                  AND admin_user.deleted_at IS NULL
                                  AND user_role.is_active = TRUE
                                  AND role.role_code = 'ADMIN'
                                  AND role.is_system = TRUE
                                  AND role.is_active = TRUE
                            ) AS is_system_admin
                        )
                        SELECT robot.mqtt_id, robot.operational_status
                        FROM robot.robot robot
                        CROSS JOIN actor_scope
                        LEFT JOIN auth.user_robot_assignment assignment
                          ON assignment.robot_id = robot.robot_id
                         AND assignment.user_id = ?
                         AND assignment.assignment_type IN (
                             'OPERATOR',
                             'OWNER'
                         )
                         AND assignment.is_active = TRUE
                         AND (
                             assignment.expires_at IS NULL
                             OR assignment.expires_at
                                > statement_timestamp()
                         )
                        WHERE robot.robot_id = ?
                          AND (
                              actor_scope.is_system_admin
                              OR assignment.user_robot_assignment_id
                                 IS NOT NULL
                          )
                        FOR UPDATE OF robot
                        """,
                resultSet -> resultSet.next()
                        ? new RobotCredentialTarget(
                                resultSet.getString("mqtt_id"),
                                resultSet.getString("operational_status"))
                        : null,
                actorUserId,
                actorUserId,
                robotId);
    }

    private RobotCredentialTarget findRobot(
            UUID robotId,
            UUID actorUserId
    ) {
        return jdbcTemplate.query("""
                        WITH actor_scope AS (
                            SELECT EXISTS (
                                SELECT 1
                                FROM auth.admin_user admin_user
                                JOIN auth.user_role user_role
                                  ON user_role.user_id = admin_user.user_id
                                JOIN auth.role role
                                  ON role.role_id = user_role.role_id
                                WHERE admin_user.user_id = ?
                                  AND admin_user.account_status = 'ACTIVE'
                                  AND admin_user.is_active = TRUE
                                  AND admin_user.deleted_at IS NULL
                                  AND user_role.is_active = TRUE
                                  AND role.role_code = 'ADMIN'
                                  AND role.is_system = TRUE
                                  AND role.is_active = TRUE
                            ) AS is_system_admin
                        )
                        SELECT robot.mqtt_id, robot.operational_status
                        FROM robot.robot robot
                        CROSS JOIN actor_scope
                        LEFT JOIN auth.user_robot_assignment assignment
                          ON assignment.robot_id = robot.robot_id
                         AND assignment.user_id = ?
                         AND assignment.assignment_type IN (
                             'OPERATOR',
                             'OWNER'
                         )
                         AND assignment.is_active = TRUE
                         AND (
                             assignment.expires_at IS NULL
                             OR assignment.expires_at
                                > statement_timestamp()
                         )
                        WHERE robot.robot_id = ?
                          AND (
                              actor_scope.is_system_admin
                              OR assignment.user_robot_assignment_id
                                 IS NOT NULL
                          )
                        """,
                resultSet -> resultSet.next()
                        ? new RobotCredentialTarget(
                                resultSet.getString("mqtt_id"),
                                resultSet.getString("operational_status"))
                        : null,
                actorUserId,
                actorUserId,
                robotId);
    }

    private StoredCredential findActiveCredential(UUID robotId) {
        return jdbcTemplate.query("""
                        SELECT
                            credential_id,
                            credential_type,
                            credential_version,
                            secret_hash,
                            secret_fingerprint,
                            credential_status,
                            issued_by,
                            issued_at,
                            expires_at,
                            activated_at,
                            revoked_by,
                            revoked_at,
                            revocation_reason
                        FROM robot.robot_credential
                        WHERE robot_id = ?
                          AND credential_type = ?
                          AND credential_status = 'ACTIVE'
                        """,
                resultSet -> resultSet.next()
                        ? mapStoredCredential(resultSet)
                        : null,
                robotId,
                CREDENTIAL_TYPE);
    }

    private StoredCredential findCredentialForUpdate(
            UUID robotId,
            UUID credentialId
    ) {
        return jdbcTemplate.query("""
                        SELECT
                            credential_id,
                            credential_type,
                            credential_version,
                            secret_hash,
                            secret_fingerprint,
                            credential_status,
                            issued_by,
                            issued_at,
                            expires_at,
                            activated_at,
                            revoked_by,
                            revoked_at,
                            revocation_reason
                        FROM robot.robot_credential
                        WHERE robot_id = ?
                          AND credential_id = ?
                        FOR UPDATE
                        """,
                resultSet -> resultSet.next()
                        ? mapStoredCredential(resultSet)
                        : null,
                robotId,
                credentialId);
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
                        WITH lifecycle AS MATERIALIZED (
                            SELECT clock_timestamp() AS changed_at
                        )
                        INSERT INTO robot.robot_credential (
                            credential_id,
                            robot_id,
                            credential_type,
                            credential_version,
                            secret_hash,
                            secret_fingerprint,
                            credential_status,
                            issued_by,
                            issued_at,
                            expires_at,
                            activated_at,
                            created_at,
                            updated_at
                        )
                        SELECT
                            ?,
                            ?,
                            ?,
                            ?,
                            ?,
                            ?,
                            'ACTIVE',
                            ?,
                            lifecycle.changed_at,
                            lifecycle.changed_at + INTERVAL '180 days',
                            lifecycle.changed_at,
                            lifecycle.changed_at,
                            lifecycle.changed_at
                        FROM lifecycle
                        RETURNING issued_at, expires_at, activated_at
                        """,
                resultSet -> {
                    if (!resultSet.next()) {
                        throw new IllegalStateException(
                                "Credential insert returned no row");
                    }
                    return new CredentialTimes(
                            resultSet.getTimestamp("issued_at").toInstant(),
                            resultSet.getTimestamp("expires_at").toInstant(),
                            resultSet.getTimestamp("activated_at").toInstant());
                },
                credentialId,
                robotId,
                CREDENTIAL_TYPE,
                credentialVersion,
                passwordHash,
                fingerprint,
                actorUserId);
    }

    private StoredCredential revokeActiveCredential(
            UUID credentialId,
            UUID actorUserId,
            String reason
    ) {
        return jdbcTemplate.query("""
                        WITH lifecycle AS MATERIALIZED (
                            SELECT clock_timestamp() AS changed_at
                        )
                        UPDATE robot.robot_credential credential
                        SET credential_status = 'REVOKED',
                            revoked_by = ?,
                            revoked_at = GREATEST(
                                lifecycle.changed_at,
                                credential.issued_at
                            ),
                            revocation_reason = ?,
                            updated_at = GREATEST(
                                lifecycle.changed_at,
                                credential.created_at
                            )
                        FROM lifecycle
                        WHERE credential.credential_id = ?
                          AND credential.credential_status = 'ACTIVE'
                        RETURNING
                            credential.credential_id,
                            credential.credential_type,
                            credential.credential_version,
                            credential.secret_hash,
                            credential.secret_fingerprint,
                            credential.credential_status,
                            credential.issued_by,
                            credential.issued_at,
                            credential.expires_at,
                            credential.activated_at,
                            credential.revoked_by,
                            credential.revoked_at,
                            credential.revocation_reason
                        """,
                resultSet -> {
                    if (!resultSet.next()) {
                        throw new BusinessException(
                                ErrorCode.ROBOT_CREDENTIAL_STATE_CONFLICT);
                    }
                    return mapStoredCredential(resultSet);
                },
                actorUserId,
                reason,
                credentialId);
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

    private void registerRollbackRestore(
            String username,
            String passwordHash
    ) {
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status == STATUS_COMMITTED) {
                            return;
                        }
                        try {
                            provisioner.restore(username, passwordHash);
                        } catch (RuntimeException exception) {
                            log.error(
                                    "Could not restore MQTT credential after "
                                            + "database rollback. username={}",
                                    username);
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

    private AuditSnapshot auditSnapshot(
            UUID robotId,
            String mqttId,
            StoredCredential credential
    ) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("robotId", robotId);
        values.put("mqttId", mqttId);
        values.put("credentialId", credential.credentialId());
        values.put("credentialType", credential.credentialType());
        values.put("credentialVersion", credential.credentialVersion());
        values.put("credentialStatus", credential.credentialStatus());
        values.put("issuedAt", credential.issuedAt());
        values.put("expiresAt", credential.expiresAt());
        values.put("activatedAt", credential.activatedAt());
        values.put("revokedAt", credential.revokedAt());
        values.put("revocationReason", credential.revocationReason());
        values.put("fingerprint", credential.secretFingerprint());
        return AuditSnapshot.allow(AUDIT_FIELDS, values);
    }

    private MqttCredentialResponse toResponse(
            StoredCredential credential
    ) {
        return new MqttCredentialResponse(
                credential.credentialId(),
                credential.credentialType(),
                credential.credentialVersion(),
                credential.credentialStatus(),
                credential.issuedBy(),
                credential.issuedAt(),
                credential.expiresAt(),
                credential.activatedAt(),
                credential.revokedBy(),
                credential.revokedAt(),
                credential.revocationReason());
    }

    private StoredCredential mapStoredCredential(
            ResultSet resultSet
    ) throws SQLException {
        return new StoredCredential(
                resultSet.getObject("credential_id", UUID.class),
                resultSet.getString("credential_type"),
                resultSet.getInt("credential_version"),
                resultSet.getString("secret_hash"),
                resultSet.getString("secret_fingerprint"),
                resultSet.getString("credential_status"),
                resultSet.getObject("issued_by", UUID.class),
                resultSet.getTimestamp("issued_at").toInstant(),
                resultSet.getTimestamp("expires_at").toInstant(),
                nullableInstant(resultSet.getTimestamp("activated_at")),
                resultSet.getObject("revoked_by", UUID.class),
                nullableInstant(resultSet.getTimestamp("revoked_at")),
                resultSet.getString("revocation_reason"));
    }

    private Instant nullableInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String normalizeReason(String reason) {
        if (reason == null) {
            throw new BusinessException(
                    ErrorCode.COMMON_VALIDATION_FAILED);
        }
        String normalized = reason.trim();
        if (normalized.isEmpty() || normalized.length() > 100) {
            throw new BusinessException(
                    ErrorCode.COMMON_VALIDATION_FAILED);
        }
        return normalized;
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
            String mqttId,
            String operationalStatus
    ) {
    }

    private record CredentialTimes(
            Instant issuedAt,
            Instant expiresAt,
            Instant activatedAt
    ) {
    }

    private record StoredCredential(
            UUID credentialId,
            String credentialType,
            int credentialVersion,
            String secretHash,
            String secretFingerprint,
            String credentialStatus,
            UUID issuedBy,
            Instant issuedAt,
            Instant expiresAt,
            Instant activatedAt,
            UUID revokedBy,
            Instant revokedAt,
            String revocationReason
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
