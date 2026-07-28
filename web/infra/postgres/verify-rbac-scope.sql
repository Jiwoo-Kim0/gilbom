\set ON_ERROR_STOP on

-- 사용자 권한 예외, 로봇 객체 범위와 마지막 ADMIN 보호를 runtime role로 검증한다.
-- 기존 관리자에게 만드는 임시 DENY를 포함해 모든 변경은 마지막에 rollback한다.
BEGIN;

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
) VALUES
    (
        '99000000-0000-4000-8000-000000000001',
        'USR-RBAC-SMOKE-A',
        '{argon2}$argon2id$v=19$m=19456,t=2,p=1$rbac-smoke-a',
        'RBAC Smoke A',
        'rbac.smoke.a@example.invalid',
        'ACTIVE',
        TRUE,
        FALSE,
        NOW(),
        'ADMIN_CREATED'
    ),
    (
        '99000000-0000-4000-8000-000000000002',
        'USR-RBAC-SMOKE-B',
        '{argon2}$argon2id$v=19$m=19456,t=2,p=1$rbac-smoke-b',
        'RBAC Smoke B',
        'rbac.smoke.b@example.invalid',
        'ACTIVE',
        TRUE,
        FALSE,
        NOW(),
        'ADMIN_CREATED'
    );

INSERT INTO auth.user_role (
    user_id,
    role_id,
    is_primary,
    is_active,
    assigned_by
)
SELECT test_user.user_id, role.role_id, TRUE, TRUE,
       '99000000-0000-4000-8000-000000000002'
FROM (
    VALUES
        ('99000000-0000-4000-8000-000000000001'::UUID),
        ('99000000-0000-4000-8000-000000000002'::UUID)
) AS test_user(user_id)
JOIN auth.role role
  ON role.role_code = 'ADMIN'
 AND role.is_system = TRUE;

INSERT INTO robot.robot (
    robot_id,
    robot_code,
    robot_name,
    serial_number,
    operational_status,
    registered_by
) VALUES (
    '99000000-0000-4000-8000-000000000101',
    'RBAC-SMOKE-ROBOT',
    'RBAC Smoke Robot',
    'RBAC-SMOKE-SERIAL',
    'ACTIVE',
    '99000000-0000-4000-8000-000000000002'
);

-- 같은 일반 permission의 ALLOW와 DENY를 함께 보존할 수 있어야 한다.
INSERT INTO auth.user_permission_override (
    permission_override_id,
    user_id,
    permission_id,
    override_effect,
    reason,
    granted_by,
    expires_at
)
SELECT override_data.override_id,
       '99000000-0000-4000-8000-000000000001',
       permission.permission_id,
       override_data.effect,
       override_data.reason,
       '99000000-0000-4000-8000-000000000002',
       NOW() + INTERVAL '1 day'
FROM (
    VALUES
        (
            '99000000-0000-4000-8000-000000000201'::UUID,
            'ALLOW',
            'RBAC schema ALLOW verification'
        ),
        (
            '99000000-0000-4000-8000-000000000202'::UUID,
            'DENY',
            'RBAC schema DENY precedence verification'
        )
) AS override_data(override_id, effect, reason)
JOIN auth.permission permission
  ON permission.permission_code = 'ROBOT_STATUS_VIEW';

INSERT INTO auth.user_robot_assignment (
    user_robot_assignment_id,
    user_id,
    robot_id,
    assignment_type,
    reason,
    assigned_by,
    expires_at
) VALUES (
    '99000000-0000-4000-8000-000000000301',
    '99000000-0000-4000-8000-000000000001',
    '99000000-0000-4000-8000-000000000101',
    'OPERATOR',
    'RBAC robot scope verification',
    '99000000-0000-4000-8000-000000000002',
    NOW() + INTERVAL '1 day'
);

DO $$
DECLARE
    robot_status_view_id UUID;
    admin_settings_manage_id UUID;
    custom_role_id UUID := '99000000-0000-4000-8000-000000000401';
    current_admin RECORD;
BEGIN
    SELECT permission_id
      INTO robot_status_view_id
      FROM auth.permission
     WHERE permission_code = 'ROBOT_STATUS_VIEW';

    SELECT permission_id
      INTO admin_settings_manage_id
      FROM auth.permission
     WHERE permission_code = 'ADMIN_SETTINGS_MANAGE';

    BEGIN
        INSERT INTO auth.user_permission_override (
            user_id,
            permission_id,
            override_effect,
            reason,
            granted_by
        ) VALUES (
            '99000000-0000-4000-8000-000000000001',
            robot_status_view_id,
            'ALLOW',
            'duplicate active effect',
            '99000000-0000-4000-8000-000000000002'
        );
        RAISE EXCEPTION 'duplicate active permission effect was accepted'
            USING ERRCODE = 'ZX020';
    EXCEPTION
        WHEN unique_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO auth.user_robot_assignment (
            user_id,
            robot_id,
            assignment_type,
            reason,
            assigned_by
        ) VALUES (
            '99000000-0000-4000-8000-000000000001',
            '99000000-0000-4000-8000-000000000101',
            'OWNER',
            'duplicate active robot scope',
            '99000000-0000-4000-8000-000000000002'
        );
        RAISE EXCEPTION 'duplicate active robot assignment was accepted'
            USING ERRCODE = 'ZX021';
    EXCEPTION
        WHEN unique_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO auth.user_robot_assignment (
            user_id,
            robot_id,
            assignment_type,
            reason,
            assigned_by,
            assigned_at,
            expires_at
        ) VALUES (
            '99000000-0000-4000-8000-000000000002',
            '99000000-0000-4000-8000-000000000101',
            'VIEWER',
            'invalid expiration',
            '99000000-0000-4000-8000-000000000002',
            NOW(),
            NOW() - INTERVAL '1 minute'
        );
        RAISE EXCEPTION 'expired robot assignment was accepted'
            USING ERRCODE = 'ZX022';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO auth.user_permission_override (
            user_id,
            permission_id,
            override_effect,
            reason,
            granted_by
        ) VALUES (
            '99000000-0000-4000-8000-000000000001',
            admin_settings_manage_id,
            'ALLOW',
            'restricted permission bypass',
            '99000000-0000-4000-8000-000000000002'
        );
        RAISE EXCEPTION 'ADMIN_SETTINGS_MANAGE user ALLOW was accepted'
            USING ERRCODE = 'ZX023';
    EXCEPTION
        WHEN insufficient_privilege THEN NULL;
    END;

    INSERT INTO auth.role (
        role_id,
        role_code,
        role_name,
        description,
        is_system,
        created_by
    ) VALUES (
        custom_role_id,
        'RBAC_SMOKE_CUSTOM',
        'RBAC Smoke Custom',
        'Restricted permission verification role',
        FALSE,
        '99000000-0000-4000-8000-000000000002'
    );

    BEGIN
        INSERT INTO auth.role_permission (
            role_id,
            permission_id,
            granted_by
        ) VALUES (
            custom_role_id,
            admin_settings_manage_id,
            '99000000-0000-4000-8000-000000000002'
        );
        RAISE EXCEPTION 'ADMIN_SETTINGS_MANAGE custom role grant was accepted'
            USING ERRCODE = 'ZX024';
    EXCEPTION
        WHEN insufficient_privilege THEN NULL;
    END;

    -- 검증용 B를 제외한 모든 현재 유효 ADMIN을 transaction 안에서 DENY해
    -- B가 실제 마지막 ADMIN인 상태를 만든다. 모든 변경은 최종 rollback된다.
    FOR current_admin IN
        SELECT DISTINCT user_role.user_id
        FROM auth.user_role user_role
        WHERE user_role.user_id
              <> '99000000-0000-4000-8000-000000000002'
          AND auth.is_active_system_admin(
              user_role.user_id,
              statement_timestamp()
          )
    LOOP
        UPDATE auth.user_permission_override
           SET is_active = FALSE,
               revoked_by =
                   '99000000-0000-4000-8000-000000000002',
               revoked_at = statement_timestamp(),
               revocation_reason =
                   'RBAC last ADMIN verification preparation',
               updated_at = statement_timestamp()
         WHERE user_id = current_admin.user_id
           AND permission_id = admin_settings_manage_id
           AND override_effect = 'DENY'
           AND is_active = TRUE;

        INSERT INTO auth.user_permission_override (
            user_id,
            permission_id,
            override_effect,
            reason,
            granted_by
        ) VALUES (
            current_admin.user_id,
            admin_settings_manage_id,
            'DENY',
            'RBAC last ADMIN verification preparation',
            '99000000-0000-4000-8000-000000000002'
        );
    END LOOP;

    IF NOT auth.is_active_system_admin(
        '99000000-0000-4000-8000-000000000002',
        statement_timestamp()
    ) THEN
        RAISE EXCEPTION 'verification survivor is not an active ADMIN'
            USING ERRCODE = 'ZX025';
    END IF;

    BEGIN
        UPDATE auth.admin_user
           SET account_status = 'DISABLED',
               is_active = FALSE,
               updated_at = statement_timestamp()
         WHERE user_id = '99000000-0000-4000-8000-000000000002';
        RAISE EXCEPTION 'last active ADMIN deactivation was accepted'
            USING ERRCODE = 'ZX026';
    EXCEPTION
        WHEN insufficient_privilege THEN NULL;
    END;

    BEGIN
        UPDATE auth.user_role
           SET is_active = FALSE,
               is_primary = FALSE,
               ended_at = statement_timestamp()
         WHERE user_id = '99000000-0000-4000-8000-000000000002'
           AND role_id = (
               SELECT role_id
               FROM auth.role
               WHERE role_code = 'ADMIN'
                 AND is_system = TRUE
           );
        RAISE EXCEPTION 'last active ADMIN role removal was accepted'
            USING ERRCODE = 'ZX027';
    EXCEPTION
        WHEN insufficient_privilege THEN NULL;
    END;

    BEGIN
        INSERT INTO auth.user_permission_override (
            user_id,
            permission_id,
            override_effect,
            reason,
            granted_by
        ) VALUES (
            '99000000-0000-4000-8000-000000000002',
            admin_settings_manage_id,
            'DENY',
            'last active ADMIN permission denial',
            '99000000-0000-4000-8000-000000000002'
        );
        RAISE EXCEPTION 'last active ADMIN permission DENY was accepted'
            USING ERRCODE = 'ZX028';
    EXCEPTION
        WHEN insufficient_privilege THEN NULL;
    END;

    BEGIN
        UPDATE auth.role_permission
           SET is_active = FALSE,
               revoked_at = statement_timestamp()
         WHERE role_id = (
               SELECT role_id
               FROM auth.role
               WHERE role_code = 'ADMIN'
                 AND is_system = TRUE
           )
           AND permission_id = admin_settings_manage_id;
        RAISE EXCEPTION 'system ADMIN management permission removal was accepted'
            USING ERRCODE = 'ZX029';
    EXCEPTION
        WHEN insufficient_privilege THEN NULL;
    END;
END;
$$;

SELECT (
    (
        SELECT COUNT(*) = 2
        FROM auth.user_permission_override permission_override
        JOIN auth.permission permission
          ON permission.permission_id = permission_override.permission_id
        WHERE permission_override.user_id =
              '99000000-0000-4000-8000-000000000001'
          AND permission.permission_code = 'ROBOT_STATUS_VIEW'
          AND permission_override.is_active = TRUE
          AND permission_override.override_effect IN ('ALLOW', 'DENY')
    )
    AND (
        SELECT assignment_type = 'OPERATOR'
               AND is_active = TRUE
               AND expires_at > statement_timestamp()
        FROM auth.user_robot_assignment
        WHERE user_robot_assignment_id =
              '99000000-0000-4000-8000-000000000301'
    )
    AND (
        SELECT COUNT(*) = 8
        FROM pg_indexes
        WHERE schemaname = 'auth'
          AND indexname IN (
              'uq_user_permission_override_active_effect',
              'ix_user_permission_override_active_user',
              'ix_user_permission_override_active_permission',
              'ix_user_permission_override_expiry',
              'uq_user_robot_assignment_active_robot',
              'ix_user_robot_assignment_active_user_scope',
              'ix_user_robot_assignment_active_robot_users',
              'ix_user_robot_assignment_expiry'
          )
    )
    AND EXISTS (
        SELECT 1
        FROM pg_trigger
        WHERE tgname = 'tr_admin_user_protect_last_admin'
          AND tgenabled <> 'D'
    )
    AND EXISTS (
        SELECT 1
        FROM pg_trigger
        WHERE tgname = 'tr_user_role_protect_last_admin'
          AND tgenabled <> 'D'
    )
    AND EXISTS (
        SELECT 1
        FROM pg_trigger
        WHERE tgname = 'tr_user_permission_override_security'
          AND tgenabled <> 'D'
    )
    AND HAS_TABLE_PRIVILEGE(
        CURRENT_USER,
        'auth.user_permission_override',
        'SELECT,INSERT,UPDATE'
    )
    AND NOT HAS_TABLE_PRIVILEGE(
        CURRENT_USER,
        'auth.user_permission_override',
        'DELETE'
    )
    AND HAS_TABLE_PRIVILEGE(
        CURRENT_USER,
        'auth.user_robot_assignment',
        'SELECT,INSERT,UPDATE'
    )
    AND NOT HAS_TABLE_PRIVILEGE(
        CURRENT_USER,
        'auth.user_robot_assignment',
        'DELETE'
    )
) AS rbac_scope_contract
\gset

\if :rbac_scope_contract
    \echo 'RBAC permission override/robot scope/last ADMIN contract: PASS'
\else
    \echo 'RBAC permission override/robot scope/last ADMIN contract: FAIL'
    \quit 1
\endif

ROLLBACK;
