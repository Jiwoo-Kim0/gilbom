package com.gilbom.auth.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.gilbom.auth.login.LoginAccountRepository;

@Testcontainers(disabledWithoutDocker = true)
class AuthenticationUserRepositoryIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE =
            DockerImageName.parse("postgis/postgis:16-3.5")
                    .asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>(POSTGIS_IMAGE)
                    .withDatabaseName("gilbom_authorization_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private AuthenticationUserRepository repository;

    @BeforeEach
    void resetDatabase() {
        dataSource = new DriverManagerDataSource(
                DATABASE.getJdbcUrl(),
                DATABASE.getUsername(),
                DATABASE.getPassword()
        );
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                DROP SCHEMA IF EXISTS
                    flyway_history,
                    auth,
                    report,
                    audit,
                    session_store,
                    request_control,
                    robot,
                    telemetry,
                    defect,
                    storage
                CASCADE
                """);
        Flyway.configure()
                .dataSource(dataSource)
                .defaultSchema("flyway_history")
                .placeholders(Map.of(
                        "applicationRole",
                        DATABASE.getUsername()
                ))
                .load()
                .migrate();
        repository = new AuthenticationUserRepository(jdbcTemplate);
    }

    @Test
    void migrationSeedsExactlyOneMfaExemptManagerAccount() {
        UUID consultantId = UUID.fromString(
                "39000000-0000-4000-8000-000000000001"
        );

        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM auth.admin_user
                WHERE mfa_required = FALSE
                """,
                Integer.class
        )).isEqualTo(1);
        assertThat(repository.isMfaExemptUser(consultantId)).isTrue();
        assertThat(new LoginAccountRepository(jdbcTemplate)
                .findByNormalizedEmail("consultant.manager@gilbom.test")
                .orElseThrow()
                .mfaRequired()).isFalse();
        assertThat(repository.findActiveUser(consultantId)
                .orElseThrow()
                .roles())
                .extracting(RoleSummaryResponse::roleCode)
                .containsExactly("MANAGER");
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM auth.mfa_factor
                WHERE user_id = ?
                """,
                Integer.class,
                consultantId
        )).isZero();
    }

    @Test
    @Tag("security-acceptance")
    void calculatesRoleUnionAllowMinusDenyWithExpiryAndActiveState() {
        UUID userId = uuid(1);
        UUID activeRoleId = uuid(101);
        UUID inactiveRoleId = uuid(102);
        createActiveUser(userId, "effective");
        createRole(activeRoleId, "EFFECTIVE_ROLE", true, userId);
        createRole(inactiveRoleId, "INACTIVE_ROLE", false, userId);
        assignRole(userId, activeRoleId, true);
        assignRole(userId, inactiveRoleId, false);

        grantRolePermission(
                activeRoleId,
                "ROBOT_STATUS_VIEW",
                userId
        );
        grantRolePermission(activeRoleId, "REPORT_VIEW", userId);
        grantInactiveRolePermission(
                activeRoleId,
                "REPORT_CREATE",
                userId
        );
        grantRolePermission(
                inactiveRoleId,
                "REPORT_APPROVE",
                userId
        );
        createInactivePermissionAndGrant(activeRoleId, userId);

        insertActiveOverride(
                userId,
                "DEFECT_EXPORT",
                "ALLOW",
                "active unexpired ALLOW",
                "1 day"
        );
        insertActiveOverride(
                userId,
                "REPORT_VIEW",
                "ALLOW",
                "ALLOW that must lose to DENY",
                null
        );
        insertActiveOverride(
                userId,
                "REPORT_VIEW",
                "DENY",
                "DENY has precedence",
                null
        );
        insertExpiredOverride(
                userId,
                "SYSTEM_LOG_VIEW",
                "ALLOW"
        );
        insertExpiredOverride(
                userId,
                "ROBOT_STATUS_VIEW",
                "DENY"
        );
        insertInactiveOverride(
                userId,
                "MISSION_MANAGE",
                "ALLOW"
        );

        AuthenticationUserRepository.AuthenticationUser result =
                repository.findActiveUser(userId).orElseThrow();

        assertThat(result.roles())
                .extracting(RoleSummaryResponse::roleCode)
                .containsExactly("EFFECTIVE_ROLE");
        assertThat(result.permissions()).containsExactly(
                "DEFECT_EXPORT",
                "ROBOT_STATUS_VIEW"
        );
    }

    @Test
    void reservesAdminSettingsForEffectiveSystemAdminAndHonorsDeny() {
        UUID firstAdminId = uuid(2);
        UUID secondAdminId = uuid(3);
        UUID customRoleId = uuid(103);
        UUID adminRoleId = permissionOrRoleId(
                "auth.role",
                "role_id",
                "role_code",
                "ADMIN"
        );
        createActiveUser(firstAdminId, "admin-a");
        createActiveUser(secondAdminId, "admin-b");
        assignRole(firstAdminId, adminRoleId, true);
        assignRole(secondAdminId, adminRoleId, true);

        assertThat(repository.findActiveUser(firstAdminId)
                .orElseThrow()
                .permissions()).contains("ADMIN_SETTINGS_MANAGE");

        insertActiveOverride(
                firstAdminId,
                "ADMIN_SETTINGS_MANAGE",
                "DENY",
                "temporarily remove management access",
                null,
                secondAdminId
        );

        assertThat(repository.findActiveUser(firstAdminId)
                .orElseThrow()
                .permissions()).doesNotContain("ADMIN_SETTINGS_MANAGE");
        assertThat(repository.findActiveUser(secondAdminId)
                .orElseThrow()
                .permissions()).contains("ADMIN_SETTINGS_MANAGE");

        assertThatThrownBy(() -> insertActiveOverride(
                firstAdminId,
                "ADMIN_SETTINGS_MANAGE",
                "ALLOW",
                "prohibited user ALLOW",
                null,
                secondAdminId
        )).isInstanceOf(DataAccessException.class);

        createRole(
                customRoleId,
                "CUSTOM_ADMIN_BYPASS",
                true,
                secondAdminId
        );
        assertThatThrownBy(() -> grantRolePermission(
                customRoleId,
                "ADMIN_SETTINGS_MANAGE",
                secondAdminId
        )).isInstanceOf(DataAccessException.class);
    }

    @Test
    void revokesSessionsForRolePermissionOverrideAndRobotScopeChanges() {
        UUID userId = uuid(4);
        UUID roleId = uuid(104);
        UUID secondRoleId = uuid(105);
        UUID robotId = uuid(204);
        createActiveUser(userId, "session-revoke");
        createRole(roleId, "SESSION_ROLE", true, userId);
        createRole(secondRoleId, "SESSION_SECOND_ROLE", true, userId);
        assignRole(userId, roleId, true);
        grantRolePermission(roleId, "ROBOT_STATUS_VIEW", userId);
        createRobot(robotId, userId);

        createAuthenticatedSession(userId, uuid(301), uuid(401));
        insertActiveOverride(
                userId,
                "DEFECT_STATUS_VIEW",
                "ALLOW",
                "session invalidation by override",
                null
        );
        assertThat(sessionCount(userId)).isZero();

        createAuthenticatedSession(userId, uuid(302), uuid(402));
        jdbcTemplate.update("""
                INSERT INTO auth.user_robot_assignment (
                    user_id,
                    robot_id,
                    assignment_type,
                    reason,
                    assigned_by
                ) VALUES (?, ?, 'VIEWER', ?, ?)
                """,
                userId,
                robotId,
                "session invalidation by robot scope",
                userId);
        assertThat(sessionCount(userId)).isZero();

        createAuthenticatedSession(userId, uuid(303), uuid(403));
        assignRole(userId, secondRoleId, false);
        assertThat(sessionCount(userId)).isZero();

        createAuthenticatedSession(userId, uuid(304), uuid(404));
        grantRolePermission(roleId, "REPORT_VIEW", userId);
        assertThat(sessionCount(userId)).isZero();

        createAuthenticatedSession(userId, uuid(305), uuid(405));
        jdbcTemplate.update("""
                UPDATE auth.role
                SET role_code = 'SESSION_ROLE_RENAMED',
                    updated_at = statement_timestamp()
                WHERE role_id = ?
                """, roleId);
        assertThat(sessionCount(userId)).isZero();

        createAuthenticatedSession(userId, uuid(306), uuid(406));
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            insertActiveOverride(
                    userId,
                    "MISSION_VIEW",
                    "ALLOW",
                    "rolled back authorization change",
                    null
            );
            assertThat(sessionCount(userId)).isZero();
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(sessionCount(userId)).isEqualTo(1);
    }

    @Test
    @Tag("security-acceptance")
    void rejectsDeactivationRoleRemovalAndDenyForLastActiveAdmin() {
        UUID adminId = uuid(5);
        UUID adminRoleId = permissionOrRoleId(
                "auth.role",
                "role_id",
                "role_code",
                "ADMIN"
        );
        createActiveUser(adminId, "last-admin");
        assignRole(adminId, adminRoleId, true);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE auth.admin_user
                SET account_status = 'DISABLED',
                    is_active = FALSE,
                    updated_at = statement_timestamp()
                WHERE user_id = ?
                """, adminId)).isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE auth.user_role
                SET is_active = FALSE,
                    ended_at = statement_timestamp()
                WHERE user_id = ?
                  AND role_id = ?
                """, adminId, adminRoleId))
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> insertActiveOverride(
                adminId,
                "ADMIN_SETTINGS_MANAGE",
                "DENY",
                "prohibited last ADMIN DENY",
                null
        )).isInstanceOf(DataAccessException.class);

        assertThat(repository.findActiveUser(adminId)
                .orElseThrow()
                .permissions()).contains("ADMIN_SETTINGS_MANAGE");
    }

    @Test
    @Tag("security-acceptance")
    void serializesConcurrentLastAdminDeactivation() throws Exception {
        UUID firstAdminId = uuid(6);
        UUID secondAdminId = uuid(7);
        UUID adminRoleId = permissionOrRoleId(
                "auth.role",
                "role_id",
                "role_code",
                "ADMIN"
        );
        createActiveUser(firstAdminId, "concurrent-a");
        createActiveUser(secondAdminId, "concurrent-b");
        assignRole(firstAdminId, adminRoleId, true);
        assignRole(secondAdminId, adminRoleId, true);

        try (
                Connection first = dataSource.getConnection();
                Connection second = dataSource.getConnection();
                ExecutorService executor = Executors.newSingleThreadExecutor()
        ) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            lockLastAdminGuard(first);
            disableUser(first, firstAdminId);

            Future<SQLException> secondFailure = executor.submit(() -> {
                try {
                    lockLastAdminGuard(second);
                    disableUser(second, secondAdminId);
                    second.commit();
                    return null;
                } catch (SQLException exception) {
                    second.rollback();
                    return exception;
                }
            });

            first.commit();
            SQLException failure = secondFailure.get(
                    Duration.ofSeconds(10).toMillis(),
                    java.util.concurrent.TimeUnit.MILLISECONDS
            );

            assertThat((Throwable) failure).isNotNull();
            assertThat(failure.getSQLState()).isEqualTo("42501");
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT auth.is_active_system_admin(
                        ?,
                        statement_timestamp()
                    )
                    """, Boolean.class, firstAdminId)).isFalse();
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT auth.is_active_system_admin(
                        ?,
                        statement_timestamp()
                    )
                    """, Boolean.class, secondAdminId)).isTrue();
        }
    }

    private void createActiveUser(UUID userId, String suffix) {
        jdbcTemplate.update("""
                INSERT INTO auth.admin_user (
                    user_id,
                    user_code,
                    password_hash,
                    display_name,
                    email,
                    account_status,
                    is_active,
                    password_change_required,
                    password_changed_at,
                    account_origin
                ) VALUES (
                    ?, ?, ?, ?, ?,
                    'ACTIVE', TRUE, FALSE, NOW(), 'ADMIN_CREATED'
                )
                """,
                userId,
                "USR-" + suffix.toUpperCase(),
                "{argon2}$argon2id$integration-test-" + suffix,
                "Authorization " + suffix,
                suffix + "@example.invalid");
    }

    private void createRole(
            UUID roleId,
            String roleCode,
            boolean active,
            UUID actorUserId
    ) {
        jdbcTemplate.update("""
                INSERT INTO auth.role (
                    role_id,
                    role_code,
                    role_name,
                    description,
                    is_system,
                    is_active,
                    created_by
                ) VALUES (?, ?, ?, ?, FALSE, ?, ?)
                """,
                roleId,
                roleCode,
                roleCode,
                "Authorization integration role",
                active,
                actorUserId);
    }

    private void assignRole(
            UUID userId,
            UUID roleId,
            boolean primary
    ) {
        jdbcTemplate.update("""
                INSERT INTO auth.user_role (
                    user_id,
                    role_id,
                    is_primary,
                    is_active,
                    assigned_by
                ) VALUES (?, ?, ?, TRUE, ?)
                """,
                userId,
                roleId,
                primary,
                userId);
    }

    private void grantRolePermission(
            UUID roleId,
            String permissionCode,
            UUID actorUserId
    ) {
        jdbcTemplate.update("""
                INSERT INTO auth.role_permission (
                    role_id,
                    permission_id,
                    is_active,
                    granted_by
                )
                SELECT ?, permission_id, TRUE, ?
                FROM auth.permission
                WHERE permission_code = ?
                """,
                roleId,
                actorUserId,
                permissionCode);
    }

    private void grantInactiveRolePermission(
            UUID roleId,
            String permissionCode,
            UUID actorUserId
    ) {
        jdbcTemplate.update("""
                INSERT INTO auth.role_permission (
                    role_id,
                    permission_id,
                    is_active,
                    granted_by,
                    granted_at,
                    revoked_at
                )
                SELECT
                    ?,
                    permission_id,
                    FALSE,
                    ?,
                    NOW() - INTERVAL '2 days',
                    NOW() - INTERVAL '1 day'
                FROM auth.permission
                WHERE permission_code = ?
                """,
                roleId,
                actorUserId,
                permissionCode);
    }

    private void createInactivePermissionAndGrant(
            UUID roleId,
            UUID actorUserId
    ) {
        UUID permissionId = uuid(501);
        jdbcTemplate.update("""
                INSERT INTO auth.permission (
                    permission_id,
                    permission_code,
                    permission_name,
                    description,
                    resource_type,
                    is_system,
                    is_active
                ) VALUES (
                    ?,
                    'INACTIVE_PERMISSION',
                    'Inactive permission',
                    'Authorization integration inactive permission',
                    'TEST',
                    FALSE,
                    FALSE
                )
                """, permissionId);
        jdbcTemplate.update("""
                INSERT INTO auth.role_permission (
                    role_id,
                    permission_id,
                    is_active,
                    granted_by
                ) VALUES (?, ?, TRUE, ?)
                """,
                roleId,
                permissionId,
                actorUserId);
    }

    private void insertActiveOverride(
            UUID userId,
            String permissionCode,
            String effect,
            String reason,
            String expiryInterval
    ) {
        insertActiveOverride(
                userId,
                permissionCode,
                effect,
                reason,
                expiryInterval,
                userId
        );
    }

    private void insertActiveOverride(
            UUID userId,
            String permissionCode,
            String effect,
            String reason,
            String expiryInterval,
            UUID actorUserId
    ) {
        jdbcTemplate.update("""
                INSERT INTO auth.user_permission_override (
                    user_id,
                    permission_id,
                    override_effect,
                    reason,
                    granted_by,
                    expires_at
                )
                SELECT
                    ?,
                    permission_id,
                    ?,
                    ?,
                    ?,
                    CASE
                        WHEN CAST(? AS TEXT) IS NULL THEN NULL
                        ELSE NOW() + CAST(? AS INTERVAL)
                    END
                FROM auth.permission
                WHERE permission_code = ?
                """,
                userId,
                effect,
                reason,
                actorUserId,
                expiryInterval,
                expiryInterval,
                permissionCode);
    }

    private void insertExpiredOverride(
            UUID userId,
            String permissionCode,
            String effect
    ) {
        jdbcTemplate.update("""
                INSERT INTO auth.user_permission_override (
                    user_id,
                    permission_id,
                    override_effect,
                    reason,
                    granted_by,
                    granted_at,
                    expires_at,
                    updated_at
                )
                SELECT
                    ?,
                    permission_id,
                    ?,
                    'expired override',
                    ?,
                    NOW() - INTERVAL '2 days',
                    NOW() - INTERVAL '1 day',
                    NOW()
                FROM auth.permission
                WHERE permission_code = ?
                """,
                userId,
                effect,
                userId,
                permissionCode);
    }

    private void insertInactiveOverride(
            UUID userId,
            String permissionCode,
            String effect
    ) {
        jdbcTemplate.update("""
                INSERT INTO auth.user_permission_override (
                    user_id,
                    permission_id,
                    override_effect,
                    reason,
                    is_active,
                    granted_by,
                    granted_at,
                    revoked_by,
                    revoked_at,
                    revocation_reason,
                    updated_at
                )
                SELECT
                    ?,
                    permission_id,
                    ?,
                    'inactive override',
                    FALSE,
                    ?,
                    NOW() - INTERVAL '2 days',
                    ?,
                    NOW() - INTERVAL '1 day',
                    'integration test revocation',
                    NOW()
                FROM auth.permission
                WHERE permission_code = ?
                """,
                userId,
                effect,
                userId,
                userId,
                permissionCode);
    }

    private void createRobot(UUID robotId, UUID actorUserId) {
        jdbcTemplate.update("""
                INSERT INTO robot.robot (
                    robot_id,
                    mqtt_id,
                    robot_name,
                    serial_number,
                    operational_status,
                    registered_by
                ) VALUES (?, ?, ?, ?, 'ACTIVE', ?)
                """,
                robotId,
                "RBT-AUTH-" + robotId.toString().substring(0, 8),
                "Authorization test robot",
                "SN-AUTH-" + robotId.toString().substring(0, 8),
                actorUserId);
    }

    private void createAuthenticatedSession(
            UUID userId,
            UUID primaryId,
            UUID sessionId
    ) {
        long now = System.currentTimeMillis();
        jdbcTemplate.update("""
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
                primaryId.toString(),
                sessionId.toString(),
                now,
                now,
                now + 1_800_000,
                userId.toString());
        jdbcTemplate.update("""
                INSERT INTO session_store.session_security_state (
                    session_primary_id,
                    user_id,
                    authentication_state,
                    absolute_expiry_time,
                    authentication_challenge_started_time,
                    authentication_challenge_expires_at,
                    authentication_challenge_failure_count,
                    created_time,
                    updated_time
                ) VALUES (
                    ?, ?, 'AUTHENTICATED', ?,
                    NULL, NULL, 0, ?, ?
                )
                """,
                primaryId.toString(),
                userId,
                now + 28_800_000,
                now,
                now);
    }

    private int sessionCount(UUID userId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM session_store.spring_session session
                JOIN session_store.session_security_state security_state
                  ON security_state.session_primary_id = session.primary_id
                WHERE security_state.user_id = ?
                """, Integer.class, userId);
        return count == null ? 0 : count;
    }

    private UUID permissionOrRoleId(
            String table,
            String idColumn,
            String codeColumn,
            String code
    ) {
        return jdbcTemplate.queryForObject(
                "SELECT " + idColumn
                        + " FROM " + table
                        + " WHERE " + codeColumn + " = ?",
                UUID.class,
                code
        );
    }

    private static void lockLastAdminGuard(Connection connection)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SELECT auth.lock_last_admin_guard()");
        }
    }

    private static void disableUser(
            Connection connection,
            UUID userId
    ) throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE auth.admin_user
                SET account_status = 'DISABLED',
                    is_active = FALSE,
                    updated_at = statement_timestamp()
                WHERE user_id = ?
                """)) {
            statement.setObject(1, userId);
            statement.executeUpdate();
        }
    }

    private static UUID uuid(long suffix) {
        return new UUID(0x7100000000004000L, 0x8000000000000000L | suffix);
    }
}
