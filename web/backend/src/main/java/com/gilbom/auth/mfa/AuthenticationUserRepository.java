package com.gilbom.auth.mfa;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuthenticationUserRepository {

    private final JdbcTemplate jdbcTemplate;

    public AuthenticationUserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<AuthenticationUser> findActiveUser(UUID userId) {
        List<UserIdentity> identities = jdbcTemplate.query(
                """
                SELECT user_id, display_name
                FROM auth.admin_user
                WHERE user_id = ?
                  AND account_status = 'ACTIVE'
                  AND is_active = TRUE
                  AND password_change_required = FALSE
                """,
                (resultSet, rowNumber) -> new UserIdentity(
                        resultSet.getObject("user_id", UUID.class),
                        resultSet.getString("display_name")
                ),
                userId
        );
        return identities.stream().findFirst().map(identity ->
                new AuthenticationUser(
                        identity.userId(),
                        identity.displayName(),
                        findRoles(identity.userId()),
                        findPermissions(identity.userId())
                )
        );
    }

    private List<RoleSummaryResponse> findRoles(UUID userId) {
        return jdbcTemplate.query(
                """
                SELECT role.role_code, role.role_name
                FROM auth.user_role user_role
                JOIN auth.role role
                  ON role.role_id = user_role.role_id
                WHERE user_role.user_id = ?
                  AND user_role.is_active = TRUE
                  AND role.is_active = TRUE
                ORDER BY
                    user_role.is_primary DESC,
                    role.display_order,
                    role.role_code
                """,
                (resultSet, rowNumber) -> new RoleSummaryResponse(
                        resultSet.getString("role_code"),
                        resultSet.getString("role_name")
                ),
                userId
        );
    }

    private List<String> findPermissions(UUID userId) {
        return jdbcTemplate.queryForList(
                """
                WITH candidate_permission AS (
                    SELECT
                        permission.permission_id,
                        permission.permission_code
                    FROM auth.user_role user_role
                    JOIN auth.role role
                      ON role.role_id = user_role.role_id
                    JOIN auth.role_permission role_permission
                      ON role_permission.role_id = role.role_id
                    JOIN auth.permission permission
                      ON permission.permission_id =
                         role_permission.permission_id
                    WHERE user_role.user_id = ?
                      AND user_role.is_active = TRUE
                      AND role.is_active = TRUE
                      AND role_permission.is_active = TRUE
                      AND permission.is_active = TRUE
                      AND (
                          permission.permission_code
                              <> 'ADMIN_SETTINGS_MANAGE'
                          OR (
                              role.role_code = 'ADMIN'
                              AND role.is_system = TRUE
                          )
                      )

                    UNION

                    SELECT
                        permission.permission_id,
                        permission.permission_code
                    FROM auth.user_permission_override
                         permission_override
                    JOIN auth.permission permission
                      ON permission.permission_id =
                         permission_override.permission_id
                    WHERE permission_override.user_id = ?
                      AND permission_override.override_effect = 'ALLOW'
                      AND permission_override.is_active = TRUE
                      AND (
                          permission_override.expires_at IS NULL
                          OR permission_override.expires_at
                              > statement_timestamp()
                      )
                      AND permission.is_active = TRUE
                      -- DB trigger와 별도로 조회 경로에서도 제한 권한 우회를 막는다.
                      AND permission.permission_code
                          <> 'ADMIN_SETTINGS_MANAGE'
                ),
                active_deny AS (
                    SELECT DISTINCT
                        permission_override.permission_id
                    FROM auth.user_permission_override
                         permission_override
                    JOIN auth.permission permission
                      ON permission.permission_id =
                         permission_override.permission_id
                    WHERE permission_override.user_id = ?
                      AND permission_override.override_effect = 'DENY'
                      AND permission_override.is_active = TRUE
                      AND (
                          permission_override.expires_at IS NULL
                          OR permission_override.expires_at
                              > statement_timestamp()
                      )
                      AND permission.is_active = TRUE
                )
                SELECT candidate_permission.permission_code
                FROM candidate_permission
                LEFT JOIN active_deny
                  ON active_deny.permission_id =
                     candidate_permission.permission_id
                WHERE active_deny.permission_id IS NULL
                ORDER BY candidate_permission.permission_code
                """,
                String.class,
                userId,
                userId,
                userId
        );
    }

    public record AuthenticationUser(
            UUID userId,
            String displayName,
            List<RoleSummaryResponse> roles,
            List<String> permissions
    ) {
        public AuthenticatedUserResponse toResponse() {
            return new AuthenticatedUserResponse(
                    userId,
                    displayName,
                    List.copyOf(roles),
                    List.copyOf(permissions)
            );
        }
    }

    private record UserIdentity(UUID userId, String displayName) {
    }
}
