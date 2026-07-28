package com.gilbom.auth.admin.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.gilbom.audit.AuditLogRepository;
import com.gilbom.audit.AuditLogService;
import com.gilbom.audit.AuditRequestContext;
import com.gilbom.audit.BestEffortAuditLogWriter;
import com.gilbom.auth.admin.AdminManagementService;
import com.gilbom.auth.admin.AdminMfaResetCommitService;
import com.gilbom.auth.admin.AdminRepository;
import com.gilbom.auth.admin.AdminDtos.MfaResetRequest;
import com.gilbom.auth.admin.AdminDtos.ApprovalRequest;
import com.gilbom.auth.mfa.TotpService;
import com.gilbom.auth.mfa.crypto.TotpSecretEncryptionService;
import com.gilbom.auth.password.PasswordHashingConfiguration;
import com.gilbom.auth.password.PasswordHashingService;
import com.gilbom.auth.session.AuthenticationSessionConfiguration;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = AdminManagementIntegrationTest.TestApplication.class,
        properties = {
                "spring.profiles.default=test",
                "spring.jpa.hibernate.ddl-auto=none"
        }
)
class AdminManagementIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE =
            DockerImageName.parse("postgis/postgis:16-3.5")
                    .asCompatibleSubstituteFor("postgres");
    private static final UUID OPERATOR_ROLE_ID = UUID.fromString(
            "00000000-0000-4000-8000-000000000003"
    );

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>(POSTGIS_IMAGE)
                    .withDatabaseName("gilbom_admin_management_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add(
                "spring.datasource.username",
                DATABASE::getUsername
        );
        registry.add(
                "spring.datasource.password",
                DATABASE::getPassword
        );
        registry.add("spring.flyway.user", DATABASE::getUsername);
        registry.add(
                "spring.flyway.password",
                DATABASE::getPassword
        );
        registry.add(
                "spring.flyway.default-schema",
                () -> "flyway_history"
        );
        registry.add(
                "spring.flyway.placeholders.applicationRole",
                DATABASE::getUsername
        );
    }

    @Autowired
    private AdminManagementService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AdminMfaResetCommitService mfaResetCommitService;

    @MockitoBean
    private TotpSecretEncryptionService encryptionService;

    @MockitoBean
    private TotpService totpService;

    private UUID actorId;
    private UUID pendingUserId;
    private UUID mfaTargetId;
    private AuditRequestContext context;

    @BeforeEach
    void createFixtures() {
        actorId = UUID.randomUUID();
        pendingUserId = UUID.randomUUID();
        mfaTargetId = UUID.randomUUID();
        context = AuditRequestContext.requestIdOnly(UUID.randomUUID());
        insertUser(actorId, "ACTIVE", true);
        insertUser(pendingUserId, "PENDING_APPROVAL", false);
        insertUser(mfaTargetId, "ACTIVE", true);
        jdbcTemplate.update(
                """
                INSERT INTO auth.user_role (
                    user_id, role_id, is_primary, assigned_by
                ) VALUES (?, ?, TRUE, ?)
                """,
                actorId,
                UUID.fromString(
                        "00000000-0000-4000-8000-000000000001"
                ),
                actorId
        );
    }

    @Test
    void approvalActivatesAssignsRoleAndWritesAuditAtomically() {
        service.approveRegistration(
                pendingUserId,
                approvalRequest(),
                actorId,
                context
        );

        Map<String, Object> account = jdbcTemplate.queryForMap(
                """
                SELECT account_status, is_active
                FROM auth.admin_user
                WHERE user_id = ?
                """,
                pendingUserId
        );
        assertThat(account)
                .containsEntry("account_status", "ACTIVE")
                .containsEntry("is_active", true);
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM auth.user_role
                WHERE user_id = ?
                  AND role_id = ?
                  AND is_active = TRUE
                  AND is_primary = TRUE
                """,
                Integer.class,
                pendingUserId,
                OPERATOR_ROLE_ID
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap(
                """
                SELECT actor_user_id, action_type, target_id, result
                FROM audit.audit_log
                WHERE request_id = ?
                """,
                context.requestId()
        ))
                .containsEntry("actor_user_id", actorId)
                .containsEntry(
                        "action_type",
                        "ADMIN_REGISTRATION_APPROVED"
                )
                .containsEntry("target_id", pendingUserId)
                .containsEntry("result", "SUCCESS");
    }

    @Test
    void auditInsertFailureRollsBackApprovalAndRoleAssignment() {
        jdbcTemplate.execute("""
                CREATE FUNCTION audit.reject_admin_approval_test()
                RETURNS TRIGGER
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    IF NEW.action_type = 'ADMIN_REGISTRATION_APPROVED'
                    THEN
                        RAISE EXCEPTION 'forced audit failure';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER tr_reject_admin_approval_test
                BEFORE INSERT ON audit.audit_log
                FOR EACH ROW
                EXECUTE FUNCTION audit.reject_admin_approval_test()
                """);
        try {
            assertThatThrownBy(() -> service.approveRegistration(
                    pendingUserId,
                    approvalRequest(),
                    actorId,
                    context
            )).isInstanceOf(DataAccessException.class);
        } finally {
            jdbcTemplate.execute("""
                    DROP TRIGGER tr_reject_admin_approval_test
                    ON audit.audit_log
                    """);
            jdbcTemplate.execute("""
                    DROP FUNCTION audit.reject_admin_approval_test()
                    """);
        }

        assertThat(jdbcTemplate.queryForMap(
                """
                SELECT account_status, is_active
                FROM auth.admin_user
                WHERE user_id = ?
                """,
                pendingUserId
        ))
                .containsEntry(
                        "account_status",
                        "PENDING_APPROVAL"
                )
                .containsEntry("is_active", false);
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM auth.user_role
                WHERE user_id = ?
                  AND is_active = TRUE
                """,
                Integer.class,
                pendingUserId
        )).isZero();
    }

    @Test
    void mfaAuditFailureRollsBackFactorTotpStepAndTargetSessions() {
        UUID actorFactorId = insertActiveFactor(actorId);
        UUID targetFactorId = insertActiveFactor(mfaTargetId);
        insertAuthenticatedSession(mfaTargetId);
        when(encryptionService.decrypt(
                eq(actorId),
                eq(actorFactorId),
                any()
        )).thenReturn(new byte[20]);
        when(totpService.verify(
                any(byte[].class),
                eq("123456"),
                any(),
                nullable(Long.class)
        )).thenReturn(OptionalLong.of(123456L));

        jdbcTemplate.execute("""
                CREATE FUNCTION audit.reject_admin_mfa_reset_test()
                RETURNS TRIGGER
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    IF NEW.action_type = 'AUTH_MFA_RESET_BY_ADMIN'
                    THEN
                        RAISE EXCEPTION 'forced MFA audit failure';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER tr_reject_admin_mfa_reset_test
                BEFORE INSERT ON audit.audit_log
                FOR EACH ROW
                EXECUTE FUNCTION audit.reject_admin_mfa_reset_test()
                """);
        try {
            assertThatThrownBy(() -> mfaResetCommitService.attempt(
                    mfaTargetId,
                    new MfaResetRequest(
                            "integration-admin-password",
                            "123456",
                            "분실 신고 확인"
                    ),
                    actorId,
                    AuditRequestContext.requestIdOnly(UUID.randomUUID())
            )).isInstanceOf(DataAccessException.class);
        } finally {
            jdbcTemplate.execute("""
                    DROP TRIGGER tr_reject_admin_mfa_reset_test
                    ON audit.audit_log
                    """);
            jdbcTemplate.execute("""
                    DROP FUNCTION audit.reject_admin_mfa_reset_test()
                    """);
        }

        assertThat(jdbcTemplate.queryForMap(
                """
                SELECT factor_status, last_successful_totp_time_step
                FROM auth.mfa_factor
                WHERE factor_id = ?
                """,
                targetFactorId
        ))
                .containsEntry("factor_status", "ACTIVE")
                .containsEntry(
                        "last_successful_totp_time_step",
                        null
                );
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT last_successful_totp_time_step
                FROM auth.mfa_factor
                WHERE factor_id = ?
                """,
                Long.class,
                actorFactorId
        )).isNull();
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM session_store.spring_session session
                JOIN session_store.session_security_state state
                  ON state.session_primary_id = session.primary_id
                WHERE state.user_id = ?
                """,
                Integer.class,
                mfaTargetId
        )).isEqualTo(1);
    }

    private ApprovalRequest approvalRequest() {
        return new ApprovalRequest(
                List.of(OPERATOR_ROLE_ID),
                OPERATOR_ROLE_ID,
                "가입 정보 검토 완료"
        );
    }

    private void insertUser(
            UUID userId,
            String status,
            boolean active
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO auth.admin_user (
                    user_id,
                    user_code,
                    password_hash,
                    display_name,
                    email,
                    is_active,
                    account_status,
                    password_change_required,
                    password_changed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, FALSE, NOW())
                """,
                userId,
                "USR-" + userId.toString().substring(0, 12),
                passwordEncoder.encode("integration-admin-password"),
                "관리 API 통합 테스트",
                userId + "@example.test",
                active,
                status
        );
    }

    private UUID insertActiveFactor(UUID userId) {
        UUID factorId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO auth.mfa_factor (
                    factor_id,
                    user_id,
                    factor_type,
                    factor_status,
                    secret_ciphertext,
                    secret_nonce,
                    secret_key_version,
                    encryption_algorithm,
                    activated_at
                ) VALUES (
                    ?, ?, 'TOTP', 'ACTIVE',
                    ?, ?, 1, 'AES-256-GCM', statement_timestamp()
                )
                """,
                factorId,
                userId,
                new byte[17],
                new byte[12]
        );
        return factorId;
    }

    private void insertAuthenticatedSession(UUID userId) {
        String primaryId = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        jdbcTemplate.update(
                """
                INSERT INTO session_store.spring_session (
                    primary_id,
                    session_id,
                    creation_time,
                    last_access_time,
                    max_inactive_interval,
                    expiry_time,
                    principal_name
                ) VALUES (?, ?, ?, ?, 1800, ?, ?)
                """,
                primaryId,
                sessionId,
                now,
                now,
                now + 1_800_000,
                userId.toString()
        );
        jdbcTemplate.update(
                """
                INSERT INTO session_store.session_security_state (
                    session_primary_id,
                    user_id,
                    authentication_state,
                    absolute_expiry_time,
                    authentication_challenge_failure_count,
                    created_time,
                    updated_time
                ) VALUES (?, ?, 'AUTHENTICATED', ?, 0, ?, ?)
                """,
                primaryId,
                userId,
                now + 28_800_000,
                now,
                now
        );
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            AdminRepository.class,
            AdminManagementService.class,
            AdminMfaResetCommitService.class,
            AuditLogService.class,
            AuditLogRepository.class,
            BestEffortAuditLogWriter.class,
            PasswordHashingConfiguration.class,
            PasswordHashingService.class,
            AuthenticationSessionConfiguration.class
    })
    static class TestApplication {
    }
}
