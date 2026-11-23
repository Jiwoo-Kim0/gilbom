\set ON_ERROR_STOP on

-- 운영 DB를 수정하지 않고 Flyway·PostGIS·승인된 시스템 seed와 runtime 최소 권한을 확인한다.
-- flyway_role과 application_role은 psql --set으로 전달한다.
SELECT (
    :'flyway_role' <> :'application_role'
    AND EXISTS (
        SELECT 1
        FROM pg_extension
        WHERE extname = 'postgis'
    )
    AND (
        SELECT COUNT(*) = 42
               AND COUNT(*) FILTER (WHERE NOT success) = 0
               AND COUNT(*) FILTER (
                   WHERE version::INTEGER BETWEEN 1 AND 42
                     AND success
               ) = 42
        FROM flyway_history.flyway_schema_history
    )
    AND (
        SELECT ARRAY_AGG(role_code ORDER BY role_code)
        FROM auth.role
        WHERE is_system
          AND is_active
    ) = ARRAY['ADMIN', 'MANAGER', 'OPERATOR']::VARCHAR[]
    AND (
        SELECT COUNT(*)
        FROM auth.permission
        WHERE is_system
          AND is_active
    ) = 18
    AND (
        SELECT COUNT(*)
        FROM auth.role_permission role_permission
        JOIN auth.role role USING (role_id)
        WHERE role.role_code = 'ADMIN'
          AND role_permission.is_active
    ) = 18
    AND (
        SELECT COUNT(*)
        FROM auth.role_permission role_permission
        JOIN auth.role role USING (role_id)
        WHERE role.role_code = 'MANAGER'
          AND role_permission.is_active
    ) = 15
    AND (
        SELECT COUNT(*)
        FROM auth.role_permission role_permission
        JOIN auth.role role USING (role_id)
        WHERE role.role_code = 'OPERATOR'
          AND role_permission.is_active
    ) = 10
    AND NOT EXISTS (
        SELECT 1
        FROM auth.role
        WHERE is_system
          AND role_code NOT IN ('ADMIN', 'MANAGER', 'OPERATOR')
    )
    AND (
        SELECT COUNT(*)
        FROM auth.admin_user
        WHERE mfa_required = FALSE
    ) = 1
    AND EXISTS (
        SELECT 1
        FROM auth.admin_user admin_user
        JOIN auth.user_role user_role
          ON user_role.user_id = admin_user.user_id
         AND user_role.is_active = TRUE
         AND user_role.is_primary = TRUE
        JOIN auth.role role
          ON role.role_id = user_role.role_id
         AND role.role_code = 'MANAGER'
         AND role.is_system = TRUE
         AND role.is_active = TRUE
        WHERE admin_user.user_id =
              '39000000-0000-4000-8000-000000000001'
          AND admin_user.normalized_email =
              'consultant.manager@gilbom.test'
          AND admin_user.account_status = 'ACTIVE'
          AND admin_user.is_active = TRUE
          AND admin_user.password_change_required = FALSE
          AND admin_user.mfa_required = FALSE
    )
    AND NOT EXISTS (
        SELECT 1
        FROM auth.mfa_factor
        WHERE user_id = '39000000-0000-4000-8000-000000000001'
    )
    AND NOT EXISTS (
        SELECT 1
        FROM robot.robot robot
        WHERE NOT EXISTS (
            SELECT 1
            FROM auth.user_robot_assignment assignment
            WHERE assignment.user_id =
                  '39000000-0000-4000-8000-000000000001'
              AND assignment.robot_id = robot.robot_id
              AND assignment.assignment_type = 'OWNER'
              AND assignment.is_active = TRUE
              AND (
                  assignment.expires_at IS NULL
                  OR assignment.expires_at > CURRENT_TIMESTAMP
              )
        )
    )
    AND NOT HAS_SCHEMA_PRIVILEGE(:'application_role', 'flyway_history', 'USAGE')
    AND NOT HAS_SCHEMA_PRIVILEGE(:'application_role', 'auth', 'CREATE')
    AND NOT HAS_SCHEMA_PRIVILEGE(:'application_role', 'storage', 'CREATE')
    AND HAS_TABLE_PRIVILEGE(
        :'application_role',
        'storage.robot_image',
        'SELECT'
    )
    AND HAS_TABLE_PRIVILEGE(
        :'application_role',
        'storage.robot_image',
        'INSERT'
    )
    AND NOT HAS_TABLE_PRIVILEGE(
        :'application_role',
        'storage.robot_image',
        'DELETE'
    )
) AS production_database_contract_ok
\gset

\if :production_database_contract_ok
    \echo 'Production PostGIS/Flyway/approved-system-seed/runtime-role contract: PASS'
\else
    \echo 'Production PostGIS/Flyway/approved-system-seed/runtime-role contract: FAIL'
    \quit 1
\endif
