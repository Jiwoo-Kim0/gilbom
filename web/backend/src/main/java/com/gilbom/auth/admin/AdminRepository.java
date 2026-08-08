package com.gilbom.auth.admin;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.gilbom.auth.admin.AdminDtos.AssignedRoleResponse;
import com.gilbom.auth.admin.AdminDtos.PermissionOverrideItem;
import com.gilbom.auth.admin.AdminDtos.PermissionOverrideResponse;
import com.gilbom.auth.admin.AdminDtos.PermissionResponse;
import com.gilbom.auth.admin.AdminDtos.RobotAssignmentItem;
import com.gilbom.auth.admin.AdminDtos.RobotAssignmentResponse;
import com.gilbom.auth.admin.AdminDtos.RoleResponse;
import com.gilbom.auth.admin.AdminDtos.UserResponse;
import com.gilbom.auth.mfa.crypto.EncryptedTotpSecret;

@Repository
public class AdminRepository {

    private static final String USER_COLUMNS = """
            SELECT
                admin_user.user_id,
                admin_user.user_code,
                admin_user.email,
                admin_user.display_name,
                admin_user.department_name,
                admin_user.job_title,
                admin_user.office_name,
                admin_user.account_status,
                admin_user.is_active,
                admin_user.created_at,
                admin_user.updated_at,
                EXISTS (
                    SELECT 1
                    FROM auth.mfa_factor factor
                    WHERE factor.user_id = admin_user.user_id
                      AND factor.factor_type = 'TOTP'
                      AND factor.factor_status = 'ACTIVE'
                ) AS mfa_enrolled
            FROM auth.admin_user admin_user
            """;

    private static final String ROLE_COLUMNS = """
            SELECT
                role.role_id,
                role.role_code,
                role.role_name,
                role.description,
                role.is_system,
                role.is_active,
                role.display_order,
                role.created_at,
                role.updated_at
            FROM auth.role role
            """;

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;

    public AdminRepository(
            JdbcTemplate jdbcTemplate,
            NamedParameterJdbcTemplate namedJdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbcTemplate = namedJdbcTemplate;
    }

    public void lockLastAdminGuard() {
        jdbcTemplate.queryForObject(
                "SELECT auth.lock_last_admin_guard()",
                Object.class
        );
    }

    public boolean isActiveSystemAdmin(UUID userId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT auth.is_active_system_admin(?)",
                Boolean.class,
                userId
        ));
    }

    public long countActiveSystemAdmins() {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM auth.admin_user admin_user
                WHERE auth.is_active_system_admin(admin_user.user_id)
                """,
                Long.class
        );
        return count == null ? 0 : count;
    }

    public List<UserResponse> findUsers(
            int page,
            int size,
            String accountStatus
    ) {
        String predicate = accountStatus == null
                ? ""
                : " WHERE admin_user.account_status = :accountStatus";
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("accountStatus", accountStatus)
                .addValue("limit", size)
                .addValue("offset", page * size);
        List<UserRow> rows = namedJdbcTemplate.query(
                USER_COLUMNS
                        + predicate
                        + " ORDER BY admin_user.created_at DESC,"
                        + " admin_user.user_id DESC"
                        + " LIMIT :limit OFFSET :offset",
                parameters,
                AdminRepository::mapUserRow
        );
        return rows.stream().map(this::withRoles).toList();
    }

    public long countUsers(String accountStatus) {
        if (accountStatus == null) {
            Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM auth.admin_user",
                    Long.class
            );
            return count == null ? 0 : count;
        }
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM auth.admin_user
                WHERE account_status = ?
                """,
                Long.class,
                accountStatus
        );
        return count == null ? 0 : count;
    }

    public Optional<UserResponse> findUser(UUID userId) {
        List<UserRow> rows = jdbcTemplate.query(
                USER_COLUMNS + " WHERE admin_user.user_id = ?",
                AdminRepository::mapUserRow,
                userId
        );
        return rows.stream().findFirst().map(this::withRoles);
    }

    public Optional<UserState> findUserForUpdate(UUID userId) {
        List<UserState> rows = jdbcTemplate.query(
                """
                SELECT user_id, account_status, is_active
                FROM auth.admin_user
                WHERE user_id = ?
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> new UserState(
                        resultSet.getObject("user_id", UUID.class),
                        resultSet.getString("account_status"),
                        resultSet.getBoolean("is_active")
                ),
                userId
        );
        return rows.stream().findFirst();
    }

    public int approvePendingUser(UUID userId) {
        return jdbcTemplate.update(
                """
                UPDATE auth.admin_user
                SET account_status = 'ACTIVE',
                    is_active = TRUE,
                    failed_login_count = 0,
                    last_failed_login_at = NULL,
                    locked_until = NULL,
                    updated_at = statement_timestamp()
                WHERE user_id = ?
                  AND account_status = 'PENDING_APPROVAL'
                  AND is_active = FALSE
                """,
                userId
        );
    }

    public int rejectPendingUser(UUID userId) {
        return jdbcTemplate.update(
                """
                UPDATE auth.admin_user
                SET account_status = 'REJECTED',
                    is_active = FALSE,
                    failed_login_count = 0,
                    last_failed_login_at = NULL,
                    locked_until = NULL,
                    updated_at = statement_timestamp()
                WHERE user_id = ?
                  AND account_status = 'PENDING_APPROVAL'
                  AND is_active = FALSE
                """,
                userId
        );
    }

    public int changeUserStatus(UUID userId, String status) {
        return jdbcTemplate.update(
                """
                UPDATE auth.admin_user
                SET account_status = ?,
                    is_active = (? = 'ACTIVE'),
                    failed_login_count = 0,
                    last_failed_login_at = NULL,
                    locked_until = NULL,
                    updated_at = statement_timestamp()
                WHERE user_id = ?
                  AND account_status IN ('ACTIVE', 'DISABLED')
                  AND account_status <> ?
                """,
                status,
                status,
                userId,
                status
        );
    }

    public List<RoleRef> findActiveRoleRefs(List<UUID> roleIds) {
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return namedJdbcTemplate.query(
                """
                SELECT role_id, role_code, is_system
                FROM auth.role
                WHERE role_id IN (:roleIds)
                  AND is_active = TRUE
                """,
                new MapSqlParameterSource("roleIds", roleIds),
                (resultSet, rowNumber) -> new RoleRef(
                        resultSet.getObject("role_id", UUID.class),
                        resultSet.getString("role_code"),
                        resultSet.getBoolean("is_system")
                )
        );
    }

    public void replaceUserRoles(
            UUID userId,
            List<UUID> roleIds,
            UUID primaryRoleId,
            UUID actorUserId
    ) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("roleIds", roleIds)
                .addValue("actorUserId", actorUserId);
        if (roleIds.isEmpty()) {
            namedJdbcTemplate.update(
                    """
                    UPDATE auth.user_role
                    SET is_active = FALSE,
                        is_primary = FALSE,
                        ended_at = statement_timestamp()
                    WHERE user_id = :userId
                      AND is_active = TRUE
                    """,
                    parameters
            );
            return;
        }

        namedJdbcTemplate.update(
                """
                UPDATE auth.user_role
                SET is_active = FALSE,
                    is_primary = FALSE,
                    ended_at = statement_timestamp()
                WHERE user_id = :userId
                  AND is_active = TRUE
                  AND role_id NOT IN (:roleIds)
                """,
                parameters
        );
        namedJdbcTemplate.update(
                """
                UPDATE auth.user_role
                SET is_primary = FALSE
                WHERE user_id = :userId
                  AND is_active = TRUE
                """,
                parameters
        );

        for (UUID roleId : roleIds) {
            jdbcTemplate.update(
                    """
                    INSERT INTO auth.user_role (
                        user_id,
                        role_id,
                        is_primary,
                        is_active,
                        assigned_by,
                        assigned_at,
                        ended_at
                    ) VALUES (
                        ?, ?, ?, TRUE, ?, statement_timestamp(), NULL
                    )
                    ON CONFLICT (user_id, role_id) DO UPDATE SET
                        is_primary = EXCLUDED.is_primary,
                        is_active = TRUE,
                        assigned_by = EXCLUDED.assigned_by,
                        assigned_at = statement_timestamp(),
                        ended_at = NULL
                    """,
                    userId,
                    roleId,
                    roleId.equals(primaryRoleId),
                    actorUserId
            );
        }
    }

    public List<RoleResponse> findRoles() {
        List<RoleRow> rows = jdbcTemplate.query(
                ROLE_COLUMNS
                        + " ORDER BY role.display_order, role.role_code",
                AdminRepository::mapRoleRow
        );
        return rows.stream().map(this::withPermissions).toList();
    }

    public Optional<RoleResponse> findRole(UUID roleId) {
        List<RoleRow> rows = jdbcTemplate.query(
                ROLE_COLUMNS + " WHERE role.role_id = ?",
                AdminRepository::mapRoleRow,
                roleId
        );
        return rows.stream().findFirst().map(this::withPermissions);
    }

    public Optional<RoleState> findRoleForUpdate(UUID roleId) {
        List<RoleState> rows = jdbcTemplate.query(
                """
                SELECT role_id, role_code, role_name, description,
                       is_system, is_active, display_order
                FROM auth.role
                WHERE role_id = ?
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> new RoleState(
                        resultSet.getObject("role_id", UUID.class),
                        resultSet.getString("role_code"),
                        resultSet.getString("role_name"),
                        resultSet.getString("description"),
                        resultSet.getBoolean("is_system"),
                        resultSet.getBoolean("is_active"),
                        resultSet.getInt("display_order")
                ),
                roleId
        );
        return rows.stream().findFirst();
    }

    public UUID insertRole(
            String roleCode,
            String roleName,
            String description,
            int displayOrder,
            UUID actorUserId
    ) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO auth.role (
                    role_code,
                    role_name,
                    description,
                    is_system,
                    is_active,
                    display_order,
                    created_by
                ) VALUES (?, ?, ?, FALSE, TRUE, ?, ?)
                RETURNING role_id
                """,
                UUID.class,
                roleCode,
                roleName,
                description,
                displayOrder,
                actorUserId
        );
    }

    public int updateRole(
            UUID roleId,
            String roleName,
            String description,
            Boolean active,
            Integer displayOrder
    ) {
        return jdbcTemplate.update(
                """
                UPDATE auth.role
                SET role_name = COALESCE(?, role_name),
                    description = COALESCE(?, description),
                    is_active = COALESCE(?, is_active),
                    display_order = COALESCE(?, display_order),
                    updated_at = statement_timestamp()
                WHERE role_id = ?
                """,
                roleName,
                description,
                active,
                displayOrder,
                roleId
        );
    }

    public int deactivateRole(UUID roleId) {
        return jdbcTemplate.update(
                """
                UPDATE auth.role
                SET is_active = FALSE,
                    updated_at = statement_timestamp()
                WHERE role_id = ?
                  AND is_active = TRUE
                """,
                roleId
        );
    }

    public List<PermissionResponse> findPermissions() {
        return jdbcTemplate.query(
                """
                SELECT permission_id, permission_code, permission_name,
                       description, resource_type, is_system, is_active,
                       display_order
                FROM auth.permission
                ORDER BY display_order, permission_code
                """,
                (resultSet, rowNumber) -> new PermissionResponse(
                        resultSet.getObject("permission_id", UUID.class),
                        resultSet.getString("permission_code"),
                        resultSet.getString("permission_name"),
                        resultSet.getString("description"),
                        resultSet.getString("resource_type"),
                        resultSet.getBoolean("is_system"),
                        resultSet.getBoolean("is_active"),
                        resultSet.getInt("display_order")
                )
        );
    }

    public List<PermissionRef> findActivePermissionRefs(
            List<UUID> permissionIds
    ) {
        if (permissionIds.isEmpty()) {
            return List.of();
        }
        return namedJdbcTemplate.query(
                """
                SELECT permission_id, permission_code
                FROM auth.permission
                WHERE permission_id IN (:permissionIds)
                  AND is_active = TRUE
                """,
                new MapSqlParameterSource(
                        "permissionIds",
                        permissionIds
                ),
                (resultSet, rowNumber) -> new PermissionRef(
                        resultSet.getObject("permission_id", UUID.class),
                        resultSet.getString("permission_code")
                )
        );
    }

    public List<UUID> findActiveRolePermissionIds(UUID roleId) {
        return jdbcTemplate.query(
                """
                SELECT permission_id
                FROM auth.role_permission
                WHERE role_id = ?
                  AND is_active = TRUE
                ORDER BY permission_id
                """,
                (resultSet, rowNumber) ->
                        resultSet.getObject("permission_id", UUID.class),
                roleId
        );
    }

    public void replaceRolePermissions(
            UUID roleId,
            List<UUID> permissionIds,
            UUID actorUserId
    ) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("roleId", roleId)
                .addValue("permissionIds", permissionIds);
        if (permissionIds.isEmpty()) {
            namedJdbcTemplate.update(
                    """
                    UPDATE auth.role_permission
                    SET is_active = FALSE,
                        revoked_at = statement_timestamp()
                    WHERE role_id = :roleId
                      AND is_active = TRUE
                    """,
                    parameters
            );
            return;
        }
        namedJdbcTemplate.update(
                """
                UPDATE auth.role_permission
                SET is_active = FALSE,
                    revoked_at = statement_timestamp()
                WHERE role_id = :roleId
                  AND is_active = TRUE
                  AND permission_id NOT IN (:permissionIds)
                """,
                parameters
        );
        for (UUID permissionId : permissionIds) {
            jdbcTemplate.update(
                    """
                    INSERT INTO auth.role_permission (
                        role_id,
                        permission_id,
                        is_active,
                        granted_by,
                        granted_at,
                        revoked_at
                    ) VALUES (?, ?, TRUE, ?, statement_timestamp(), NULL)
                    ON CONFLICT (role_id, permission_id) DO UPDATE SET
                        is_active = TRUE,
                        granted_by = EXCLUDED.granted_by,
                        granted_at = statement_timestamp(),
                        revoked_at = NULL
                    """,
                    roleId,
                    permissionId,
                    actorUserId
            );
        }
    }

    public List<PermissionOverrideResponse> findPermissionOverrides(
            UUID userId
    ) {
        return jdbcTemplate.query(
                """
                SELECT
                    permission_override.permission_override_id,
                    permission_override.permission_id,
                    permission.permission_code,
                    permission_override.override_effect,
                    permission_override.reason,
                    permission_override.granted_at,
                    permission_override.expires_at
                FROM auth.user_permission_override permission_override
                JOIN auth.permission permission
                  ON permission.permission_id =
                     permission_override.permission_id
                WHERE permission_override.user_id = ?
                  AND permission_override.is_active = TRUE
                ORDER BY permission.permission_code,
                         permission_override.override_effect
                """,
                (resultSet, rowNumber) ->
                        new PermissionOverrideResponse(
                                resultSet.getObject(
                                        "permission_override_id",
                                        UUID.class
                                ),
                                resultSet.getObject(
                                        "permission_id",
                                        UUID.class
                                ),
                                resultSet.getString("permission_code"),
                                AdminDtos.OverrideEffect.valueOf(
                                        resultSet.getString(
                                                "override_effect"
                                        )
                                ),
                                resultSet.getString("reason"),
                                instant(resultSet, "granted_at"),
                                instant(resultSet, "expires_at")
                        ),
                userId
        );
    }

    public void replacePermissionOverrides(
            UUID userId,
            List<PermissionOverrideItem> overrides,
            UUID actorUserId,
            String replacementReason
    ) {
        jdbcTemplate.update(
                """
                UPDATE auth.user_permission_override
                SET is_active = FALSE,
                    revoked_by = ?,
                    revoked_at = statement_timestamp(),
                    revocation_reason = ?,
                    updated_at = statement_timestamp()
                WHERE user_id = ?
                  AND is_active = TRUE
                """,
                actorUserId,
                replacementReason,
                userId
        );
        for (PermissionOverrideItem override : overrides) {
            jdbcTemplate.update(
                    """
                    INSERT INTO auth.user_permission_override (
                        user_id,
                        permission_id,
                        override_effect,
                        reason,
                        is_active,
                        granted_by,
                        expires_at
                    ) VALUES (?, ?, ?, ?, TRUE, ?, ?)
                    """,
                    userId,
                    override.permissionId(),
                    override.effect().name(),
                    override.reason().trim(),
                    actorUserId,
                    timestamp(override.expiresAt())
            );
        }
    }

    public List<RobotAssignmentResponse> findRobotAssignments(UUID userId) {
        return jdbcTemplate.query(
                """
                SELECT
                    assignment.user_robot_assignment_id,
                    assignment.robot_id,
                    robot.robot_name,
                    assignment.assignment_type,
                    assignment.reason,
                    assignment.assigned_at,
                    assignment.expires_at
                FROM auth.user_robot_assignment assignment
                JOIN robot.robot
                  ON robot.robot_id = assignment.robot_id
                WHERE assignment.user_id = ?
                  AND assignment.is_active = TRUE
                ORDER BY robot.robot_name, assignment.robot_id
                """,
                (resultSet, rowNumber) ->
                        new RobotAssignmentResponse(
                                resultSet.getObject(
                                        "user_robot_assignment_id",
                                        UUID.class
                                ),
                                resultSet.getObject(
                                        "robot_id",
                                        UUID.class
                                ),
                                resultSet.getString("robot_name"),
                                AdminDtos.RobotAssignmentType.valueOf(
                                        resultSet.getString(
                                                "assignment_type"
                                        )
                                ),
                                resultSet.getString("reason"),
                                instant(resultSet, "assigned_at"),
                                instant(resultSet, "expires_at")
                        ),
                userId
        );
    }

    public int countActiveRobots(List<UUID> robotIds) {
        if (robotIds.isEmpty()) {
            return 0;
        }
        Integer count = namedJdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM robot.robot
                WHERE robot_id IN (:robotIds)
                """,
                new MapSqlParameterSource("robotIds", robotIds),
                Integer.class
        );
        return count == null ? 0 : count;
    }

    public void replaceRobotAssignments(
            UUID userId,
            List<RobotAssignmentItem> assignments,
            UUID actorUserId,
            String replacementReason
    ) {
        jdbcTemplate.update(
                """
                UPDATE auth.user_robot_assignment
                SET is_active = FALSE,
                    ended_by = ?,
                    ended_at = statement_timestamp(),
                    end_reason = ?,
                    updated_at = statement_timestamp()
                WHERE user_id = ?
                  AND is_active = TRUE
                """,
                actorUserId,
                replacementReason,
                userId
        );
        for (RobotAssignmentItem assignment : assignments) {
            jdbcTemplate.update(
                    """
                    INSERT INTO auth.user_robot_assignment (
                        user_id,
                        robot_id,
                        assignment_type,
                        reason,
                        is_active,
                        assigned_by,
                        expires_at
                    ) VALUES (?, ?, ?, ?, TRUE, ?, ?)
                    """,
                    userId,
                    assignment.robotId(),
                    assignment.assignmentType().name(),
                    assignment.reason().trim(),
                    actorUserId,
                    timestamp(assignment.expiresAt())
            );
        }
    }

    public Optional<MfaReauthentication> findMfaReauthenticationForUpdate(
            UUID userId
    ) {
        List<MfaReauthentication> rows = jdbcTemplate.query(
                """
                SELECT
                    admin_user.password_hash,
                    factor.factor_id,
                    factor.secret_ciphertext,
                    factor.secret_nonce,
                    factor.secret_key_version,
                    factor.last_successful_totp_time_step
                FROM auth.admin_user admin_user
                JOIN auth.mfa_factor factor
                  ON factor.user_id = admin_user.user_id
                WHERE admin_user.user_id = ?
                  AND admin_user.account_status = 'ACTIVE'
                  AND admin_user.is_active = TRUE
                  AND factor.factor_type = 'TOTP'
                  AND factor.factor_status = 'ACTIVE'
                FOR UPDATE OF admin_user, factor
                """,
                (resultSet, rowNumber) -> {
                    long lastStep = resultSet.getLong(
                            "last_successful_totp_time_step"
                    );
                    boolean lastStepWasNull = resultSet.wasNull();
                    return new MfaReauthentication(
                            resultSet.getString("password_hash"),
                            resultSet.getObject("factor_id", UUID.class),
                            new EncryptedTotpSecret(
                                    resultSet.getBytes(
                                            "secret_ciphertext"
                                    ),
                                    resultSet.getBytes("secret_nonce"),
                                    resultSet.getInt(
                                            "secret_key_version"
                                    )
                            ),
                            lastStepWasNull ? null : lastStep
                    );
                },
                userId
        );
        return rows.stream().findFirst();
    }

    public int advanceTotpStep(
            UUID userId,
            UUID factorId,
            long successfulStep
    ) {
        return jdbcTemplate.update(
                """
                UPDATE auth.mfa_factor
                SET last_successful_totp_time_step = ?,
                    last_used_at = statement_timestamp(),
                    updated_at = statement_timestamp()
                WHERE factor_id = ?
                  AND user_id = ?
                  AND factor_status = 'ACTIVE'
                  AND (
                    last_successful_totp_time_step IS NULL
                    OR last_successful_totp_time_step < ?
                  )
                """,
                successfulStep,
                factorId,
                userId,
                successfulStep
        );
    }

    public int revokeMfaFactors(UUID userId) {
        return jdbcTemplate.update(
                """
                UPDATE auth.mfa_factor
                SET factor_status = 'REVOKED',
                    revoked_at = statement_timestamp(),
                    updated_at = statement_timestamp()
                WHERE user_id = ?
                  AND factor_type = 'TOTP'
                  AND factor_status IN ('PENDING', 'ACTIVE')
                """,
                userId
        );
    }

    public int revokeAllSessions(UUID userId) {
        Integer deleted = jdbcTemplate.queryForObject(
                """
                WITH deletion_context AS MATERIALIZED (
                    SELECT set_config(
                        'gilbom.session_delete_reason',
                        'MFA_RESET',
                        TRUE
                    ) AS reason
                )
                SELECT auth.revoke_user_authorization_sessions(?)
                FROM deletion_context
                """,
                Integer.class,
                userId
        );
        return deleted == null ? 0 : deleted;
    }

    private UserResponse withRoles(UserRow row) {
        List<AssignedRoleResponse> roles = jdbcTemplate.query(
                """
                SELECT role.role_id, role.role_code, role.role_name,
                       user_role.is_primary
                FROM auth.user_role user_role
                JOIN auth.role role
                  ON role.role_id = user_role.role_id
                WHERE user_role.user_id = ?
                  AND user_role.is_active = TRUE
                ORDER BY user_role.is_primary DESC,
                         role.display_order,
                         role.role_code
                """,
                (resultSet, rowNumber) -> new AssignedRoleResponse(
                        resultSet.getObject("role_id", UUID.class),
                        resultSet.getString("role_code"),
                        resultSet.getString("role_name"),
                        resultSet.getBoolean("is_primary")
                ),
                row.userId()
        );
        return new UserResponse(
                row.userId(),
                row.userCode(),
                row.email(),
                row.displayName(),
                row.departmentName(),
                row.jobTitle(),
                row.officeName(),
                row.accountStatus(),
                row.active(),
                row.mfaEnrolled(),
                roles,
                row.createdAt(),
                row.updatedAt()
        );
    }

    private RoleResponse withPermissions(RoleRow row) {
        List<String> permissionCodes = jdbcTemplate.query(
                """
                SELECT permission.permission_code
                FROM auth.role_permission role_permission
                JOIN auth.permission permission
                  ON permission.permission_id =
                     role_permission.permission_id
                WHERE role_permission.role_id = ?
                  AND role_permission.is_active = TRUE
                  AND permission.is_active = TRUE
                ORDER BY permission.display_order,
                         permission.permission_code
                """,
                (resultSet, rowNumber) ->
                        resultSet.getString("permission_code"),
                row.roleId()
        );
        return new RoleResponse(
                row.roleId(),
                row.roleCode(),
                row.roleName(),
                row.description(),
                row.system(),
                row.active(),
                row.displayOrder(),
                permissionCodes,
                row.createdAt(),
                row.updatedAt()
        );
    }

    private static UserRow mapUserRow(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        return new UserRow(
                resultSet.getObject("user_id", UUID.class),
                resultSet.getString("user_code"),
                resultSet.getString("email"),
                resultSet.getString("display_name"),
                resultSet.getString("department_name"),
                resultSet.getString("job_title"),
                resultSet.getString("office_name"),
                resultSet.getString("account_status"),
                resultSet.getBoolean("is_active"),
                resultSet.getBoolean("mfa_enrolled"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at")
        );
    }

    private static RoleRow mapRoleRow(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        return new RoleRow(
                resultSet.getObject("role_id", UUID.class),
                resultSet.getString("role_code"),
                resultSet.getString("role_name"),
                resultSet.getString("description"),
                resultSet.getBoolean("is_system"),
                resultSet.getBoolean("is_active"),
                resultSet.getInt("display_order"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at")
        );
    }

    private static Instant instant(
            ResultSet resultSet,
            String column
    ) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    public record UserState(
            UUID userId,
            String accountStatus,
            boolean active
    ) {
    }

    public record RoleRef(
            UUID roleId,
            String roleCode,
            boolean system
    ) {
    }

    public record PermissionRef(
            UUID permissionId,
            String permissionCode
    ) {
    }

    public record RoleState(
            UUID roleId,
            String roleCode,
            String roleName,
            String description,
            boolean system,
            boolean active,
            int displayOrder
    ) {
    }

    public record MfaReauthentication(
            String passwordHash,
            UUID factorId,
            EncryptedTotpSecret encryptedSecret,
            Long lastSuccessfulTotpStep
    ) {
    }

    private record UserRow(
            UUID userId,
            String userCode,
            String email,
            String displayName,
            String departmentName,
            String jobTitle,
            String officeName,
            String accountStatus,
            boolean active,
            boolean mfaEnrolled,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    private record RoleRow(
            UUID roleId,
            String roleCode,
            String roleName,
            String description,
            boolean system,
            boolean active,
            int displayOrder,
            Instant createdAt,
            Instant updatedAt
    ) {
    }
}
