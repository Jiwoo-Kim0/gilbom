package com.gilbom.robot.credential;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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
import com.gilbom.robot.data.api.response.MqttCredentialRegistryStatusResponse;

import io.micrometer.core.instrument.MeterRegistry;

@Service
public class MqttCredentialRegistryService {

    static final String CHECK_METRIC =
            "gilbom.mqtt.credential.registry.checks";
    static final String DRIFT_GAUGE =
            "gilbom.mqtt.credential.registry.drift.current";
    static final String INSPECTION_ERROR_GAUGE =
            "gilbom.mqtt.credential.registry.inspection.errors.current";

    private static final Logger log = LoggerFactory.getLogger(
            MqttCredentialRegistryService.class);
    private static final Set<String> AUDIT_FIELDS = Set.of(
            "robotId",
            "mqttId",
            "registryStatus",
            "activeCredentialId",
            "activeCredentialVersion");

    private final JdbcTemplate jdbcTemplate;
    private final MqttCredentialProvisioner provisioner;
    private final AuditLogService auditLogService;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final AtomicInteger currentDriftCount = new AtomicInteger();
    private final AtomicInteger currentInspectionErrorCount =
            new AtomicInteger();

    public MqttCredentialRegistryService(
            JdbcTemplate jdbcTemplate,
            MqttCredentialProvisioner provisioner,
            AuditLogService auditLogService,
            Clock clock,
            MeterRegistry meterRegistry
    ) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
        this.provisioner = Objects.requireNonNull(provisioner);
        this.auditLogService = Objects.requireNonNull(auditLogService);
        this.clock = Objects.requireNonNull(clock);
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
        meterRegistry.gauge(DRIFT_GAUGE, currentDriftCount);
        meterRegistry.gauge(
                INSPECTION_ERROR_GAUGE,
                currentInspectionErrorCount);
    }

    @Transactional(readOnly = true)
    public MqttCredentialRegistryStatusResponse status(
            UUID robotId,
            UUID actorUserId
    ) {
        RegistryTarget target = findScopedTarget(
                robotId,
                actorUserId,
                false);
        if (target == null) {
            throw new BusinessException(ErrorCode.ROBOT_NOT_FOUND);
        }
        return toResponse(evaluate(target, clock.instant()));
    }

    @Transactional
    public MqttCredentialRegistryStatusResponse reconcile(
            UUID robotId,
            UUID actorUserId,
            String reason,
            UUID requestId
    ) {
        requireTransactionSynchronization();
        String normalizedReason = normalizeReason(reason);
        RegistryTarget target = findScopedTarget(
                robotId,
                actorUserId,
                true);
        if (target == null) {
            throw new BusinessException(ErrorCode.ROBOT_NOT_FOUND);
        }

        Instant checkedAt = clock.instant();
        MqttCredentialProvisioner.CredentialRegistrySnapshot beforeBroker =
                provisioner.inspect(target.mqttId());
        RegistryCheck before = evaluate(
                target,
                beforeBroker,
                checkedAt);
        if (!before.status().driftDetected()) {
            return toResponse(before);
        }

        if (target.activePasswordHash() == null) {
            provisioner.revoke(
                    target.mqttId(),
                    beforeBroker.passwordHash());
        } else {
            provisioner.restore(
                    target.mqttId(),
                    target.activePasswordHash());
        }
        registerRollback(
                target,
                beforeBroker);

        RegistryCheck after = evaluate(target, clock.instant());
        if (after.status().driftDetected()) {
            throw new BusinessException(
                    ErrorCode.MQTT_CREDENTIAL_REGISTRY_DRIFT);
        }

        UUID auditTargetId = target.activeCredentialId() == null
                ? target.robotId()
                : target.activeCredentialId();
        auditLogService.appendRequired(new AuditLogRecord(
                AuditRequestContext.requestIdOnly(requestId),
                actorUserId,
                AuditActorType.USER,
                AuditAction.ROBOT_CREDENTIAL_REGISTRY_RECONCILED
                        .databaseValue(),
                AuditTargetType.ROBOT_CREDENTIAL,
                auditTargetId,
                auditSnapshot(before),
                auditSnapshot(after),
                normalizedReason,
                AuditResult.SUCCESS
        ));
        return toResponse(after);
    }

    public ScanSummary scanRegistry() {
        List<RegistryTarget> targets = findAllTargets();
        int driftCount = 0;
        int inspectionErrorCount = 0;
        Instant checkedAt = clock.instant();

        for (RegistryTarget target : targets) {
            try {
                RegistryCheck check = evaluate(target, checkedAt);
                if (check.status().driftDetected()) {
                    driftCount++;
                    incrementCheckMetric("drift");
                    log.warn(
                            "MQTT credential registry drift detected. "
                                    + "robotId={} status={}",
                            target.robotId(),
                            check.status().name());
                } else {
                    incrementCheckMetric("in_sync");
                }
            } catch (RuntimeException exception) {
                inspectionErrorCount++;
                incrementCheckMetric("inspection_error");
                log.error(
                        "MQTT credential registry inspection failed. "
                                + "robotId={} errorType={}",
                        target.robotId(),
                        exception.getClass().getSimpleName());
            }
        }

        currentDriftCount.set(driftCount);
        currentInspectionErrorCount.set(inspectionErrorCount);
        return new ScanSummary(
                targets.size(),
                driftCount,
                inspectionErrorCount,
                checkedAt);
    }

    private RegistryCheck evaluate(
            RegistryTarget target,
            Instant checkedAt
    ) {
        return evaluate(
                target,
                provisioner.inspect(target.mqttId()),
                checkedAt);
    }

    private RegistryCheck evaluate(
            RegistryTarget target,
            MqttCredentialProvisioner.CredentialRegistrySnapshot broker,
            Instant checkedAt
    ) {
        RegistryStatus status;
        if (target.activePasswordHash() == null) {
            status = broker.present()
                    ? RegistryStatus.ORPHANED
                    : RegistryStatus.IN_SYNC;
        } else if (!broker.present()) {
            status = RegistryStatus.MISSING;
        } else if (!target.activePasswordHash()
                .equals(broker.passwordHash())) {
            status = RegistryStatus.MISMATCH;
        } else {
            status = RegistryStatus.IN_SYNC;
        }
        return new RegistryCheck(target, status, checkedAt);
    }

    private RegistryTarget findScopedTarget(
            UUID robotId,
            UUID actorUserId,
            boolean lockRobot
    ) {
        String lockClause = lockRobot ? " FOR UPDATE OF robot" : "";
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
                        SELECT
                            robot.robot_id,
                            robot.mqtt_id,
                            credential.credential_id,
                            credential.credential_version,
                            credential.secret_hash
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
                        LEFT JOIN robot.robot_credential credential
                          ON credential.robot_id = robot.robot_id
                         AND credential.credential_type = 'MQTT_PASSWORD'
                         AND credential.credential_status = 'ACTIVE'
                        WHERE robot.robot_id = ?
                          AND (
                              actor_scope.is_system_admin
                              OR assignment.user_robot_assignment_id
                                 IS NOT NULL
                          )
                        """ + lockClause,
                resultSet -> resultSet.next()
                        ? new RegistryTarget(
                                resultSet.getObject(
                                        "robot_id",
                                        UUID.class),
                                resultSet.getString("mqtt_id"),
                                resultSet.getObject(
                                        "credential_id",
                                        UUID.class),
                                (Integer) resultSet.getObject(
                                        "credential_version"),
                                resultSet.getString("secret_hash"))
                        : null,
                actorUserId,
                actorUserId,
                robotId);
    }

    private List<RegistryTarget> findAllTargets() {
        return jdbcTemplate.query("""
                        SELECT
                            robot.robot_id,
                            robot.mqtt_id,
                            credential.credential_id,
                            credential.credential_version,
                            credential.secret_hash
                        FROM robot.robot robot
                        LEFT JOIN robot.robot_credential credential
                          ON credential.robot_id = robot.robot_id
                         AND credential.credential_type = 'MQTT_PASSWORD'
                         AND credential.credential_status = 'ACTIVE'
                        ORDER BY robot.robot_id
                        """,
                (resultSet, rowNumber) -> new RegistryTarget(
                        resultSet.getObject("robot_id", UUID.class),
                        resultSet.getString("mqtt_id"),
                        resultSet.getObject(
                                "credential_id",
                                UUID.class),
                        (Integer) resultSet.getObject(
                                "credential_version"),
                        resultSet.getString("secret_hash")));
    }

    private void registerRollback(
            RegistryTarget target,
            MqttCredentialProvisioner.CredentialRegistrySnapshot before
    ) {
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int transactionStatus) {
                        if (transactionStatus == STATUS_COMMITTED) {
                            return;
                        }
                        try {
                            if (before.present()) {
                                provisioner.restore(
                                        target.mqttId(),
                                        before.passwordHash());
                            } else if (target.activePasswordHash() != null) {
                                provisioner.revoke(
                                        target.mqttId(),
                                        target.activePasswordHash());
                            }
                        } catch (RuntimeException exception) {
                            log.error(
                                    "Could not restore MQTT credential "
                                            + "registry after database "
                                            + "rollback. robotId={} "
                                            + "errorType={}",
                                    target.robotId(),
                                    exception.getClass().getSimpleName());
                        }
                    }
                });
    }

    private AuditSnapshot auditSnapshot(RegistryCheck check) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("robotId", check.target().robotId());
        values.put("mqttId", check.target().mqttId());
        values.put("registryStatus", check.status().name());
        values.put(
                "activeCredentialId",
                check.target().activeCredentialId());
        values.put(
                "activeCredentialVersion",
                check.target().activeCredentialVersion());
        return AuditSnapshot.allow(AUDIT_FIELDS, values);
    }

    private MqttCredentialRegistryStatusResponse toResponse(
            RegistryCheck check
    ) {
        return new MqttCredentialRegistryStatusResponse(
                check.target().robotId(),
                check.target().mqttId(),
                check.status().name(),
                check.status().driftDetected(),
                check.target().activeCredentialId(),
                check.target().activeCredentialVersion(),
                check.checkedAt());
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

    private void requireTransactionSynchronization() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException(
                    "Credential registry reconciliation requires "
                            + "transaction synchronization");
        }
    }

    private void incrementCheckMetric(String result) {
        meterRegistry.counter(
                CHECK_METRIC,
                "result",
                result).increment();
    }

    public record ScanSummary(
            int checkedCount,
            int driftCount,
            int inspectionErrorCount,
            Instant checkedAt
    ) {
    }

    private enum RegistryStatus {
        IN_SYNC(false),
        MISSING(true),
        MISMATCH(true),
        ORPHANED(true);

        private final boolean driftDetected;

        RegistryStatus(boolean driftDetected) {
            this.driftDetected = driftDetected;
        }

        boolean driftDetected() {
            return driftDetected;
        }
    }

    private record RegistryTarget(
            UUID robotId,
            String mqttId,
            UUID activeCredentialId,
            Integer activeCredentialVersion,
            String activePasswordHash
    ) {
    }

    private record RegistryCheck(
            RegistryTarget target,
            RegistryStatus status,
            Instant checkedAt
    ) {
    }
}
